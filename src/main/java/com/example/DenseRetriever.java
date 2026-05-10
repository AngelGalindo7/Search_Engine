package com.example;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class DenseRetriever implements Retriever {

    // Lazy-loaded flat embedding matrix; index i → 384-dim L2-normalized vector for docId i.
    private static volatile float[][] embeddings;

    private static float[][] loadEmbeddings() {
        if (embeddings != null) return embeddings;
        synchronized (DenseRetriever.class) {
            if (embeddings != null) return embeddings;
            Path path = Path.of("blog_embeddings.bin");
            if (!Files.exists(path)) {
                System.err.println("WARN: blog_embeddings.bin not found; DenseRetriever will return no results");
                embeddings = new float[0][];
                return embeddings;
            }
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                // Header: [N:int32][dim:int32] little-endian
                ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                while (header.hasRemaining()) if (ch.read(header) < 0) break;
                header.flip();
                int n   = header.getInt();
                int dim = header.getInt();
                float[][] mat = new float[n][dim];
                ByteBuffer row = ByteBuffer.allocate(dim * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) {
                    row.clear();
                    while (row.hasRemaining()) if (ch.read(row) < 0) break;
                    row.flip();
                    for (int j = 0; j < dim; j++) mat[i][j] = row.getFloat();
                }
                embeddings = mat;
                System.out.printf("DenseRetriever: loaded %d × %d embeddings%n", n, dim);
            } catch (IOException e) {
                e.printStackTrace();
                embeddings = new float[0][];
            }
            return embeddings;
        }
    }

    @Override
    public List<SearchResult> search(String query, int topN) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        float[] qEmbed;
        try {
            qEmbed = BlogReranker.embed(query);
        } catch (Exception e) {
            System.err.println("DenseRetriever: query embed failed: " + e.getMessage());
            return Collections.emptyList();
        }

        float[][] allEmbed = loadEmbeddings();
        if (allEmbed.length == 0) return Collections.emptyList();

        // Min-heap keyed on cosine score so we maintain only topN candidates while
        // scanning all docs, avoiding a full sort of 13k entries.
        PriorityQueue<double[]> heap = new PriorityQueue<>(
                topN + 1,
                Comparator.comparingDouble(e -> e[1])
        );

        for (int docId = 0; docId < allEmbed.length; docId++) {
            if (BlogSearch.docMetadata.get(docId) == null) continue;
            double cos = BlogReranker.cosine(qEmbed, allEmbed[docId]);
            heap.offer(new double[]{docId, cos});
            if (heap.size() > topN) heap.poll(); // evict lowest scorer
        }

        List<double[]> sorted = new ArrayList<>(heap);
        sorted.sort(Comparator.comparingDouble((double[] e) -> e[1]).reversed());

        List<SearchResult> out = new ArrayList<>(sorted.size());
        for (double[] e : sorted) {
            int docId = (int) e[0];
            double cos = e[1];
            BlogDocMeta m = BlogSearch.docMetadata.get(docId);
            out.add(new SearchResult(docId, cos, m.title, m.company, m.url));
        }
        return out;
    }
}
