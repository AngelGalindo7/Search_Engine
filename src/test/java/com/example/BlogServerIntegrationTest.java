package com.example;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BlogServerIntegrationTest {

    private static HttpServer server;
    private static HttpClient client;
    private static String baseUrl;

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(Files.exists(Path.of("blog_index.bin")), "blog_index.bin missing — skip integration tests");
        BlogSearch.loadDependencies();
        server = BlogServer.start(0);
        int port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void healthReturnsOk() throws Exception {
        HttpResponse<String> resp = get("/health");
        assertEquals(200, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals("ok", body.get("status").getAsString());
        assertTrue(body.get("docs").getAsInt() > 0);
    }

    @Test
    void searchReturnsExpectedShape() throws Exception {
        HttpResponse<String> resp = get("/search?q=rust+async&top=5");
        assertEquals(200, resp.statusCode());
        assertEquals("*", resp.headers().firstValue("access-control-allow-origin").orElse(""));
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals("rust async", body.get("query").getAsString());
        assertNotNull(body.get("tookMs"));
        JsonArray results = body.getAsJsonArray("results");
        assertTrue(results.size() > 0, "expected at least one result for 'rust async'");
        JsonObject first = results.get(0).getAsJsonObject();
        assertNotNull(first.get("docId"));
        assertNotNull(first.get("score"));
        assertNotNull(first.get("title"));
        assertNotNull(first.get("company"));
        assertNotNull(first.get("url"));
    }

    @Test
    void emptyQueryReturns200WithEmptyResults() throws Exception {
        HttpResponse<String> resp = get("/search?q=");
        assertEquals(200, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("totalResults").getAsInt());
    }

    @Test
    void missingQueryReturns200WithEmptyResults() throws Exception {
        HttpResponse<String> resp = get("/search");
        assertEquals(200, resp.statusCode());
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertEquals(0, body.get("totalResults").getAsInt());
    }

    @Test
    void corsPreflightReturns204WithHeaders() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/search"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, resp.statusCode());
        assertEquals("*", resp.headers().firstValue("access-control-allow-origin").orElse(""));
        assertTrue(resp.headers().firstValue("access-control-allow-methods").orElse("").contains("GET"));
    }

    @Test
    void topParamIsClamped() throws Exception {
        HttpResponse<String> resp = get("/search?q=engineering&top=99999");
        JsonObject body = JsonParser.parseString(resp.body()).getAsJsonObject();
        assertTrue(body.getAsJsonArray("results").size() <= 50, "top should clamp to MAX_TOP=50");
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        HttpResponse<String> resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/html"));
        assertTrue(resp.body().contains("Engineering Blog Search"));
    }

    @Test
    void staticCssServedWithCssContentType() throws Exception {
        HttpResponse<String> resp = get("/static/style.css");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("text/css"));
    }

    @Test
    void staticJsServedWithJsContentType() throws Exception {
        HttpResponse<String> resp = get("/static/search.js");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("content-type").orElse("").startsWith("application/javascript"));
    }

    @Test
    void pathTraversalRejected() throws Exception {
        HttpResponse<String> resp = get("/static/../etc/passwd");
        assertEquals(400, resp.statusCode());
    }

    @Test
    void unknownStaticReturns404() throws Exception {
        HttpResponse<String> resp = get("/static/does-not-exist.css");
        assertEquals(404, resp.statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
