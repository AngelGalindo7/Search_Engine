package com.example;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class IndexBootstrap {

    static final String[] FILES = {
            "blog_index.bin",
            "blog_doc_meta.txt",
            "blog_token_meta.txt",
            "blog_embeddings.bin",
    };

    private IndexBootstrap() {}

    public static void bootstrapIfNeeded() throws IOException, InterruptedException {
        bootstrap(System.getenv("INDEX_RELEASE_URL"), Path.of(""), defaultClient());
    }

    static void bootstrap(String baseUrl, Path dir, HttpClient client)
            throws IOException, InterruptedException {
        List<String> missing = new ArrayList<>();
        for (String f : FILES) {
            if (!Files.exists(dir.resolve(f))) missing.add(f);
        }
        if (missing.isEmpty()) return;

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Index files missing locally and INDEX_RELEASE_URL not set: " + missing);
        }
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

        for (String f : missing) {
            String url = baseUrl + "/" + f;
            System.out.println("Bootstrapping " + f + " from " + url);
            Path target = dir.resolve(f);
            Path tmp = dir.resolve(f + ".part");

            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("Download failed: HTTP " + resp.statusCode() + " for " + url);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("  -> " + Files.size(target) + " bytes");
        }
    }

    static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
