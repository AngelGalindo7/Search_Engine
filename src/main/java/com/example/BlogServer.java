package com.example;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    public static void main(String[] args) throws IOException {
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
        server.createContext("/health", BlogServer::handleHealth);
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

        long startNs = System.nanoTime();
        List<SearchResult> results = BlogSearch.searchResults(q, topN);
        long tookMs = (System.nanoTime() - startNs) / 1_000_000L;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", q);
        body.put("tookMs", tookMs);
        body.put("totalResults", results.size());
        body.put("results", results);
        sendJson(ex, 200, body);
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
