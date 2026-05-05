package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexBootstrapTest {

    @Test
    void noOpWhenAllFilesPresent(@TempDir Path dir) throws Exception {
        for (String f : IndexBootstrap.FILES) {
            Files.writeString(dir.resolve(f), "stub");
        }
        HttpClient client = IndexBootstrap.defaultClient();
        assertDoesNotThrow(() -> IndexBootstrap.bootstrap(null, dir, client));
        for (String f : IndexBootstrap.FILES) {
            assertTrue(Files.exists(dir.resolve(f)));
            assertTrue(Files.size(dir.resolve(f)) > 0);
        }
    }

    @Test
    void throwsWhenFilesMissingAndUrlUnset(@TempDir Path dir) {
        HttpClient client = IndexBootstrap.defaultClient();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> IndexBootstrap.bootstrap(null, dir, client));
        assertTrue(ex.getMessage().contains("INDEX_RELEASE_URL"));
        assertTrue(ex.getMessage().contains("blog_index.bin"));
    }

    @Test
    void throwsWhenFilesMissingAndUrlBlank(@TempDir Path dir) {
        HttpClient client = IndexBootstrap.defaultClient();
        assertThrows(IllegalStateException.class,
                () -> IndexBootstrap.bootstrap("   ", dir, client));
    }
}
