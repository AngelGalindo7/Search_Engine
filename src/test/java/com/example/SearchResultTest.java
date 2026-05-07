package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchResultTest {

    private final Gson gson = new Gson();

    @Test
    void serializesAllFieldsToJson() {
        SearchResult r = new SearchResult(42, 3.14, "Title", "Acme Eng", "https://example.com/post");
        JsonObject json = JsonParser.parseString(gson.toJson(r)).getAsJsonObject();
        assertEquals(42, json.get("docId").getAsInt());
        assertEquals(3.14, json.get("score").getAsDouble(), 1e-9);
        assertEquals("Title", json.get("title").getAsString());
        assertEquals("Acme Eng", json.get("company").getAsString());
        assertEquals("https://example.com/post", json.get("url").getAsString());
    }

    @Test
    void roundTripsThroughGson() {
        SearchResult original = new SearchResult(7, 12.5, "Async Rust", "fasterthanli.me", "https://fasterthanli.me/x");
        SearchResult parsed = gson.fromJson(gson.toJson(original), SearchResult.class);
        assertEquals(original.docId, parsed.docId);
        assertEquals(original.score, parsed.score, 1e-9);
        assertEquals(original.title, parsed.title);
        assertEquals(original.company, parsed.company);
        assertEquals(original.url, parsed.url);
    }

    @Test
    void handlesEmptyAndUnicodeFields() {
        SearchResult r = new SearchResult(0, 0.0, "Tëst — em-dash", "", "https://example.com/é");
        SearchResult parsed = gson.fromJson(gson.toJson(r), SearchResult.class);
        assertEquals("Tëst — em-dash", parsed.title);
        assertEquals("", parsed.company);
        assertTrue(parsed.url.endsWith("é"));
    }
}
