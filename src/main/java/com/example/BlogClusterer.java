package com.example;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Offline k-means clustering over blog_embeddings.bin.
 * Run once after the full pipeline (crawl → index → pagerank → embed).
 * Writes eval/clusters.json for the /clusters HTTP endpoint.
 */
public class BlogClusterer {

    private static final int K      = 20;
    private static final int ITERS  = 100;
    private static final long SEED  = 42L;
    // Stop early when fewer than 0.1% of docs change cluster.
    private static final double CONVERGE_FRAC = 0.001;

    // Titles of the stopwords used to extract cluster labels from post titles.
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
        "a", "an", "the", "in", "on", "at", "of", "for", "to", "and", "or",
        "is", "are", "was", "were", "be", "been", "how", "what", "why", "when",
        "with", "by", "from", "into", "this", "that", "it", "we", "our", "your",
        "i", "using", "building", "introducing"
    ));

    public static void main(String[] args) throws IOException {
        BlogSearch.loadDependencies();
        System.out.printf("Loaded %d docs%n", BlogSearch.TOTAL_DOCS);

        float[][] emb = loadEmbeddings();
        int n   = emb.length;
        int dim = n > 0 ? emb[0].length : 0;
        System.out.printf("Embeddings: %d × %d%n", n, dim);

        int[] assign = kmeans(emb, n, dim);

        writeJson(emb, assign, n);
    }

    // -------------------------------------------------------------------------
    // Embedding loader — same byte layout as BlogSearch.loadDocEmbeddings()
    // -------------------------------------------------------------------------

    private static float[][] loadEmbeddings() throws IOException {
        Path path = Path.of("blog_embeddings.bin");
        if (!Files.exists(path)) throw new IOException("blog_embeddings.bin not found");
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            // header: [N:int32][dim:int32] little-endian
            ByteBuffer hdr = ByteBuffer.allocate(8);  // default BIG_ENDIAN, matches writer
            while (hdr.hasRemaining()) if (ch.read(hdr) < 0) break;
            hdr.flip();
            int n   = hdr.getInt();
            int dim = hdr.getInt();
            float[][] mat = new float[n][dim];
            ByteBuffer row = ByteBuffer.allocate(dim * 4);
            for (int i = 0; i < n; i++) {
                row.clear();
                while (row.hasRemaining()) if (ch.read(row) < 0) break;
                row.flip();
                for (int j = 0; j < dim; j++) mat[i][j] = row.getFloat();
            }
            System.out.printf("Read %d × %d floats from blog_embeddings.bin%n", n, dim);
            return mat;
        }
    }

    // -------------------------------------------------------------------------
    // Lloyd's k-means with cosine similarity (vectors assumed L2-normalised)
    // -------------------------------------------------------------------------

    private static int[] kmeans(float[][] emb, int n, int dim) {
        // Seed k centroids from distinct random docs
        Random rng = new Random(SEED);
        float[][] centroids = new float[K][dim];
        Set<Integer> chosen = new HashSet<>();
        for (int c = 0; c < K; c++) {
            int idx;
            do { idx = rng.nextInt(n); } while (chosen.contains(idx));
            chosen.add(idx);
            centroids[c] = Arrays.copyOf(emb[idx], dim);
        }

        int[] assign = new int[n];
        Arrays.fill(assign, 0);

        for (int iter = 0; iter < ITERS; iter++) {
            // Assignment step: nearest centroid by cosine = max dot product (L2-normalised vecs)
            int changed = 0;
            for (int i = 0; i < n; i++) {
                int best = 0;
                double bestDot = dot(emb[i], centroids[0], dim);
                for (int c = 1; c < K; c++) {
                    double d = dot(emb[i], centroids[c], dim);
                    if (d > bestDot) { bestDot = d; best = c; }
                }
                if (best != assign[i]) { assign[i] = best; changed++; }
            }

            if ((iter + 1) % 10 == 0) {
                System.out.printf("Iter %d: %d reassigned%n", iter + 1, changed);
            }

            // Update step: recompute centroids as mean of assigned docs, then L2-normalise
            float[][] newCentroids = new float[K][dim];
            int[] counts = new int[K];
            for (int i = 0; i < n; i++) {
                int c = assign[i];
                counts[c]++;
                for (int j = 0; j < dim; j++) newCentroids[c][j] += emb[i][j];
            }
            for (int c = 0; c < K; c++) {
                if (counts[c] == 0) {
                    // Empty cluster: reinitialise to a random doc
                    newCentroids[c] = Arrays.copyOf(emb[rng.nextInt(n)], dim);
                } else {
                    float norm = 0f;
                    for (int j = 0; j < dim; j++) {
                        newCentroids[c][j] /= counts[c];
                        norm += newCentroids[c][j] * newCentroids[c][j];
                    }
                    norm = (float) Math.sqrt(norm);
                    if (norm > 1e-8f) for (int j = 0; j < dim; j++) newCentroids[c][j] /= norm;
                }
            }
            centroids = newCentroids;

            if (changed < n * CONVERGE_FRAC) {
                System.out.printf("Converged after %d iters (%d reassigned)%n", iter + 1, changed);
                break;
            }
        }
        return assign;
    }

    private static double dot(float[] a, float[] b, int dim) {
        double s = 0;
        for (int j = 0; j < dim; j++) s += a[j] * b[j];
        return s;
    }

    // -------------------------------------------------------------------------
    // JSON output
    // -------------------------------------------------------------------------

    private static void writeJson(float[][] emb, int[] assign, int n) throws IOException {
        Map<Integer, BlogDocMeta> meta = BlogSearch.docMetadata;

        // Collect doc IDs per cluster
        @SuppressWarnings("unchecked")
        List<Integer>[] clusters = new List[K];
        for (int c = 0; c < K; c++) clusters[c] = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            // Only include docs that have metadata
            if (meta.containsKey(i)) clusters[assign[i]].add(i);
        }

        JsonArray clustersArr = new JsonArray();
        int[] sizes = new int[K];

        for (int c = 0; c < K; c++) {
            List<Integer> docs = clusters[c];
            sizes[c] = docs.size();

            // Sort by pageRank desc for top-posts selection and label derivation
            docs.sort((a, b) -> Double.compare(
                meta.getOrDefault(b, dummy()).pageRank,
                meta.getOrDefault(a, dummy()).pageRank
            ));

            // Label: 3 most frequent non-stopword words in top-10 titles
            List<Integer> top10 = docs.subList(0, Math.min(10, docs.size()));
            String label = deriveLabel(c, top10, meta);
            String[] topKw = label.split(" ", 3);

            // Top-5 posts by pageRank
            List<Integer> top5 = docs.subList(0, Math.min(5, docs.size()));
            JsonArray postsArr = new JsonArray();
            for (int docId : top5) {
                BlogDocMeta m = meta.get(docId);
                if (m == null) continue;
                JsonObject p = new JsonObject();
                p.addProperty("docId",    docId);
                p.addProperty("title",    m.title    != null ? m.title    : "");
                p.addProperty("company",  m.company  != null ? m.company  : "");
                p.addProperty("url",      m.url      != null ? m.url      : "");
                p.addProperty("pageRank", m.pageRank);
                p.addProperty("postDate", m.postDate != null ? m.postDate : "");
                postsArr.add(p);
            }

            JsonArray kwArr = new JsonArray();
            for (String kw : topKw) kwArr.add(kw);

            JsonObject cl = new JsonObject();
            cl.addProperty("id",           c);
            cl.addProperty("label",        label);
            cl.add("top_keywords",         kwArr);
            cl.addProperty("post_count",   docs.size());
            cl.add("posts",                postsArr);
            clustersArr.add(cl);
        }

        JsonObject root = new JsonObject();
        root.addProperty("k",          K);
        root.addProperty("total_docs", n);
        root.add("clusters",           clustersArr);

        Files.createDirectories(Path.of("eval"));
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        Files.writeString(Path.of("eval/clusters.json"), json);

        int min = Arrays.stream(sizes).min().getAsInt();
        int max = Arrays.stream(sizes).max().getAsInt();
        double mean = Arrays.stream(sizes).average().getAsDouble();
        System.out.printf("Written eval/clusters.json. Cluster sizes: min=%d, max=%d, mean=%.0f%n",
            min, max, mean);
    }

    // Returns "word1 word2 word3" label from the top-10 docs' titles.
    private static String deriveLabel(int clusterId, List<Integer> top10, Map<Integer, BlogDocMeta> meta) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (int docId : top10) {
            BlogDocMeta m = meta.get(docId);
            if (m == null || m.title == null) continue;
            for (String raw : m.title.split("[^a-zA-Z0-9]+")) {
                String w = raw.toLowerCase();
                if (w.length() < 2 || STOPWORDS.contains(w)) continue;
                freq.merge(w, 1, Integer::sum);
            }
        }
        // Sort by frequency desc, then alphabetically for stable ties
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> b.getValue() != a.getValue()
            ? b.getValue() - a.getValue()
            : a.getKey().compareTo(b.getKey()));
        StringBuilder sb = new StringBuilder();
        int taken = 0;
        for (Map.Entry<String, Integer> e : entries) {
            if (taken == 3) break;
            if (taken > 0) sb.append(' ');
            sb.append(e.getKey());
            taken++;
        }
        return taken == 0 ? "cluster-" + clusterId : sb.toString();
    }

    private static BlogDocMeta dummy() {
        return new BlogDocMeta("", 0, "", 0.0, "", "");
    }
}
