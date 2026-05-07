package com.example;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogServerTest {

    @Test
    void parsesSimpleQueryString() {
        Map<String, String> p = BlogServer.parseQueryString(URI.create("http://x/search?q=rust"));
        assertEquals("rust", p.get("q"));
    }

    @Test
    void decodesUrlEncodedQueryString() {
        Map<String, String> p = BlogServer.parseQueryString(URI.create("http://x/search?q=rust+async&top=5"));
        assertEquals("rust async", p.get("q"));
        assertEquals("5", p.get("top"));
    }

    @Test
    void decodesPercentEncodedUnicode() {
        Map<String, String> p = BlogServer.parseQueryString(URI.create("http://x/search?q=%C3%A9"));
        assertEquals("é", p.get("q"));
    }

    @Test
    void emptyQueryStringYieldsEmptyMap() {
        assertTrue(BlogServer.parseQueryString(URI.create("http://x/search")).isEmpty());
        assertTrue(BlogServer.parseQueryString(URI.create("http://x/search?")).isEmpty());
    }

    @Test
    void parsePositiveIntFallsBackOnNullAndJunk() {
        assertEquals(10, BlogServer.parsePositiveInt(null, 10));
        assertEquals(10, BlogServer.parsePositiveInt("", 10));
        assertEquals(10, BlogServer.parsePositiveInt("not-a-number", 10));
        assertEquals(10, BlogServer.parsePositiveInt("-5", 10));
        assertEquals(10, BlogServer.parsePositiveInt("0", 10));
        assertEquals(7, BlogServer.parsePositiveInt("7", 10));
    }

    @Test
    void clampTopBoundsEnforced() {
        assertEquals(1, BlogServer.clampTop(0));
        assertEquals(1, BlogServer.clampTop(-3));
        assertEquals(10, BlogServer.clampTop(10));
        assertEquals(50, BlogServer.clampTop(50));
        assertEquals(50, BlogServer.clampTop(999));
    }
}
