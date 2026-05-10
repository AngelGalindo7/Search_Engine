package com.example;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class BlogServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_TOP = 10;
    private static final int MAX_TOP = 50;
    private static final Gson GSON = new Gson();

    // Cached clusters.json content; populated on first /clusters request.
    private static volatile String clustersCache;

    public static void main(String[] args) throws IOException, InterruptedException {
        IndexBootstrap.bootstrapIfNeeded();
        int port = parsePort(args);
        BlogSearch.loadDependencies();
        System.out.printf("Loaded %d tokens, %d docs, avg length %.0f%n",
                BlogSearch.tokenMetadata.size(), BlogSearch.TOTAL_DOCS, BlogSearch.AVG_DOC_LENGTH);

        HttpServer server = start(port);
        System.out.println("Listening on http://localhost:" + server.getAddress().getPort());
    }

    public static HttpServer start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/search", BlogServer::handleSearch);
        server.createContext("/top", BlogServer::handleTop);
        server.createContext("/stats", BlogServer::handleStats);
        server.createContext("/health", BlogServer::handleHealth);
        server.createContext("/clusters", BlogServer::handleClusters);
        server.createContext("/more-like", BlogServer::handleMoreLike);
        server.createContext("/", BlogServer::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server;
    }

    private static int parsePort(String[] args) {
        if (args.length > 0) {
            try { return Integer.parseInt(args[0]); } catch (NumberFormatException ignored) {}
        }
        String envPort = System.getenv("PORT");
        if (envPort != null) {
            try { return Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_PORT;
    }

    static void handleSearch(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        Map<String, String> params = parseQueryString(ex.getRequestURI());
        String q = params.getOrDefault("q", "").trim();
        int topN = clampTop(parsePositiveInt(params.get("top"), DEFAULT_TOP));
        boolean explain = "1".equals(params.get("explain"));

        long startNs = System.nanoTime();
        List<SearchResult> results = BlogSearch.searchResults(q, topN, explain);
        long tookMs = (System.nanoTime() - startNs) / 1_000_000L;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q);
        body.put("tookMs", tookMs);
        body.put("totalResults", results.size());
        body.put("results", results);
        sendJson(ex, 200, body);
    }

    static void handleTop(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        Map<String, String> params = parseQueryString(ex.getRequestURI());
        int n = clampTop(parsePositiveInt(params.get("n"), DEFAULT_TOP));
        List<SearchResult> results = BlogSearch.topByPageRank(n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("results", results);
        sendJson(ex, 200, body);
    }

    static void handleStats(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        sendJson(ex, 200, BlogSearch.stats());
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String path = ex.getRequestURI().getPath();
        // Reject path traversal before any classpath lookup.
        if (path.contains("..") || path.contains("\\")) {
            sendJson(ex, 400, Map.of("error", "Bad path"));
            return;
        }
        if ("/".equals(path) || "/index.html".equals(path)) {
            path = "/static/index.html";
        }
        if (!path.startsWith("/static/")) {
            sendJson(ex, 404, Map.of("error", "Not found"));
            return;
        }

        try (InputStream is = BlogServer.class.getResourceAsStream(path)) {
            if (is == null) {
                sendJson(ex, 404, Map.of("error", "Not found"));
                return;
            }
            byte[] body = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentTypeFor(path));
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
            ex.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    static String contentTypeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "css"  -> "text/css; charset=utf-8";
            case "js"   -> "application/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg"  -> "image/svg+xml";
            case "png"  -> "image/png";
            case "ico"  -> "image/x-icon";
            default     -> "application/octet-stream";
        };
    }

    static void handleMoreLike(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1); ex.close(); return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed")); return;
        }
        Map<String, String> params = parseQueryString(ex.getRequestURI());
        String docIdStr = params.get("docId");
        if (docIdStr == null) {
            sendJson(ex, 400, Map.of("error", "Missing docId parameter")); return;
        }
        int docId;
        try { docId = Integer.parseInt(docIdStr); }
        catch (NumberFormatException e) {
            sendJson(ex, 400, Map.of("error", "Invalid docId")); return;
        }
        int top = Math.min(parsePositiveInt(params.get("top"), 5), 20);
        String exclude = params.get("exclude");
        long startNs = System.nanoTime();
        java.util.List<SearchResult> results = BlogSearch.findNeighbors(docId, top, exclude);
        long tookMs = (System.nanoTime() - startNs) / 1_000_000L;
        if (results == null) {
            sendJson(ex, 503, Map.of("error", "Embedding index not loaded")); return;
        }
        sendJson(ex, 200, Map.of("queryDocId", docId, "results", results, "tookMs", tookMs));
    }

    static void handleClusters(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }
        if (clustersCache == null) {
            synchronized (BlogServer.class) {
                if (clustersCache == null) {
                    Path p = Path.of("eval/clusters.json");
                    if (Files.exists(p)) {
                        clustersCache = Files.readString(p, StandardCharsets.UTF_8);
                    }
                }
            }
        }
        if (clustersCache == null) {
            byte[] err = "{\"error\":\"Cluster index not built. Run BlogClusterer first.\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(404, err.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(err); }
            return;
        }
        byte[] bytes = clustersCache.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static void handleHealth(HttpExchange ex) throws IOException {
        addCorsHeaders(ex);
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("docs", BlogSearch.TOTAL_DOCS);
        body.put("tokens", BlogSearch.tokenMetadata == null ? 0 : BlogSearch.tokenMetadata.size());
        sendJson(ex, 200, body);
    }

    private static void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseQueryString(URI uri) {
        Map<String, String> params = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return params;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            params.put(key, val);
        }
        return params;
    }

    static int parsePositiveInt(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static int clampTop(int n) {
        return Math.min(Math.max(1, n), MAX_TOP);
    }
}
