package com.example;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Blog-corpus parallel of {@link Indexer}.
 *
 * Reads BLOGS/{company}/{hash}.json (output of {@link Crawler}), builds an inverted index,
 * and writes three artifacts independent from the DEV pipeline:
 *   - blog_index.bin       (binary postings, identical 12-byte stride to index.bin)
 *   - blog_token_meta.txt  (per-token offset/length into blog_index.bin)
 *   - blog_doc_meta.txt    (per-doc 7-field schema: docId url length title pageRank company postDate)
 *
 * Reuses Posting / TokenMeta / TokenResult from Indexer.java since those types are corpus-agnostic.
 * Uses {@link BlogDocMeta} for the enriched 6-field doc metadata.
 */
public class BlogIndexer {

    static int docId = 0;
    static int filesScanned = 0;
    static int dupUrlSkipped = 0;
    static String bigramStrategy = "all-tags";

    // Dense-embedding state. Populated inline during indexDocument when the model
    // is reachable; left empty (and embeddings file skipped) on model-load failure
    // so the BM25 index alone is still buildable on a flaky-network machine.
    static boolean embedAvailable = false;
    static int embeddingsFailed = 0;
    static int embeddingTextCharCap = 2000;       // ~256 tokens after HF tokenizer
    static Map<Integer, float[]> docEmbeddings = new HashMap<>();

    static Map<String, List<Posting>> invertedIndex = new HashMap<>();
    static Map<Integer, BlogDocMeta> docMetadata = new HashMap<>();
    static Map<String, TokenMeta> tokenMetadata = new HashMap<>();
    static Set<String> seenUrls = new HashSet<>();

    public static void main(String[] args) {
        long startMs = System.currentTimeMillis();

        // Eagerly load the embedding model so a missing/cold model surfaces at
        // start of run, not after we've already paid the indexing cost. On
        // failure we set embedAvailable=false and continue BM25-only.
        try {
            BlogReranker.load();
            embedAvailable = true;
            System.out.println("Embedding model loaded.");
        } catch (Exception e) {
            embedAvailable = false;
            System.err.println("WARN: embedding model unavailable, skipping blog_embeddings.bin: "
                    + e.getMessage());
        }

        indexDirectory();
        System.out.println("FINISHED indexing " + docId + " documents");
        System.out.println("Size of invertedIndex " + invertedIndex.size());
        String probe = "engineering";
        List<Posting> probePostings = invertedIndex.get(probe);
        System.out.println("Size of \"" + probe + "\" postings " + (probePostings == null ? 0 : probePostings.size()));
        System.out.println("Size of docMetadata " + docMetadata.size());
        if (embedAvailable) {
            System.out.println("Embeddings produced: " + docEmbeddings.size()
                    + " (failed: " + embeddingsFailed + ")");
        }

        writeBlogIndex();
        writeBlogDocMetadata();
        writeBlogTokenMetadata();
        if (embedAvailable) writeBlogEmbeddings();

        Map<Integer, BlogDocMeta> doctest = readBlogDocMetadata();
        System.out.println("SIZE :" + doctest.size());

        BlogDocMeta t = doctest.get(0);
        if (t != null) {
            System.out.println("doc id 0: " + t.url + " | len=" + t.length + " | title=" + t.title
                    + " | company=" + t.company + " | date=" + t.postDate + " | pr=" + t.pageRank);
        }

        long durationSec = (System.currentTimeMillis() - startMs) / 1000;
        writeIndexManifest(durationSec);
    }

    private static void writeIndexManifest(long durationSec) {
        try {
            long postingsTotal = 0;
            long totalDocLen = 0;
            for (List<Posting> ps : invertedIndex.values()) postingsTotal += ps.size();
            for (BlogDocMeta m : docMetadata.values()) totalDocLen += m.length;

            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("timestamp", java.time.Instant.now().toString());
            m.addProperty("duration_sec", durationSec);
            m.addProperty("input_dir", "BLOGS");
            m.addProperty("input_files_scanned", filesScanned);
            m.addProperty("docs_indexed", docId);
            m.addProperty("docs_skipped_dup_url", dupUrlSkipped);
            m.addProperty("tokens_unique", invertedIndex.size());
            m.addProperty("postings_total", postingsTotal);
            m.addProperty("avg_doc_length", docId == 0 ? 0 : (int) (totalDocLen / docId));
            m.addProperty("bigram_strategy", bigramStrategy);
            m.addProperty("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
            m.addProperty("embed_available", embedAvailable);
            if (embedAvailable) {
                m.addProperty("embed_model", BlogReranker.MODEL_URL);
                m.addProperty("embed_dim", BlogReranker.EMBEDDING_DIM);
                m.addProperty("embed_text_char_cap", embeddingTextCharCap);
                m.addProperty("embeddings_written", docEmbeddings.size());
                m.addProperty("embeddings_failed", embeddingsFailed);
            }

            java.nio.file.Files.createDirectories(java.nio.file.Path.of("eval"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("eval/index_manifest.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(m));
            System.out.println("Written eval/index_manifest.json");
        } catch (Exception e) {
            System.err.println("index manifest write failed: " + e.getMessage());
        }
    }

    public static void writeBlogIndex() {
        List<String> sortedTokens = new ArrayList<>(invertedIndex.keySet());
        Collections.sort(sortedTokens);

        try (FileChannel channel = FileChannel.open(Path.of("blog_index.bin"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (String term : sortedTokens) {
                List<Posting> postings = invertedIndex.get(term);
                int df = postings.size();
                long offset = channel.position();
                int length = df * Integer.BYTES * 3;

                ByteBuffer buf = ByteBuffer.allocate(length);
                for (Posting p : postings) {
                    buf.putInt(p.docId);
                    buf.putInt(p.tf);
                    buf.putInt(p.tagMask);
                }
                buf.flip();
                channel.write(buf);

                tokenMetadata.put(term, new TokenMeta(df, offset, length));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void writeBlogDocMetadata() {
        try (FileWriter writer = new FileWriter("blog_doc_meta.txt")) {
            for (Map.Entry<Integer, BlogDocMeta> entry : docMetadata.entrySet()) {
                int id = entry.getKey();
                BlogDocMeta meta = entry.getValue();
                String safeTitle = (meta.title == null || meta.title.isEmpty()) ? "N/A" : meta.title.replace(" ", "^");
                String safeCompany = (meta.company == null || meta.company.isEmpty()) ? "N/A" : meta.company.replace(" ", "^");
                String safeDate = (meta.postDate == null || meta.postDate.isEmpty()) ? "N/A" : meta.postDate;
                writer.write(id + " " + meta.url + " " + meta.length + " " + safeTitle + " "
                        + meta.pageRank + " " + safeCompany + " " + safeDate + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Map<Integer, BlogDocMeta> readBlogDocMetadata() {
        Map<Integer, BlogDocMeta> meta = new HashMap<>();
        try (Scanner scanner = new Scanner(new File("blog_doc_meta.txt"))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" ");
                if (parts.length != 7) continue;

                int id = Integer.parseInt(parts[0]);
                String url = parts[1];
                int length = Integer.parseInt(parts[2]);
                String title = parts[3].equals("N/A") ? "" : parts[3].replace("^", " ");
                double pageRank = Double.parseDouble(parts[4]);
                String company = parts[5].equals("N/A") ? "" : parts[5].replace("^", " ");
                String postDate = parts[6].equals("N/A") ? "" : parts[6];

                meta.put(id, new BlogDocMeta(url, length, title, pageRank, company, postDate));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return meta;
    }

    public static void writeBlogTokenMetadata() {
        try (FileWriter writer = new FileWriter("blog_token_meta.txt")) {
            for (Map.Entry<String, TokenMeta> entry : tokenMetadata.entrySet()) {
                String token = entry.getKey().replace(" ", "^");
                TokenMeta tm = entry.getValue();
                writer.write(token + " " + tm.df + " " + tm.offset + " " + tm.length + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Map<String, TokenMeta> readBlogTokenMetadata() {
        Map<String, TokenMeta> meta = new HashMap<>();
        try (Scanner scanner = new Scanner(new File("blog_token_meta.txt"))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" ");
                if (parts.length != 4) continue;

                String token = parts[0].replace("^", " ");
                int df = Integer.parseInt(parts[1]);
                long offset = Long.parseLong(parts[2]);
                int length = Integer.parseInt(parts[3]);
                meta.put(token, new TokenMeta(df, offset, length));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return meta;
    }

    public static void indexDirectory() {
        List<String> filePaths = Parser.parseDirectory("BLOGS");
        if (filePaths == null) return;
        filesScanned = filePaths.size();

        for (String filePath : filePaths) {
            try (Reader reader = new FileReader(filePath)) {
                Gson gson = new Gson();
                Map<String, String> jsonMap = gson.fromJson(reader, Map.class);

                String docUrl = Parser.normalizeUrl(jsonMap.get("url"));
                List<Node> docNodes = Parser.parseContent(jsonMap.get("content"));
                String rssTitle = jsonMap.get("title");
                String company = jsonMap.get("company");
                String postDate = jsonMap.get("date");

                indexDocument(docUrl, docNodes, rssTitle, company, postDate);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void indexDocument(String url, List<Node> docNodes, String rssTitle, String company, String postDate) {
        if (url == null) return;
        if (seenUrls.contains(url)) { dupUrlSkipped++; return; }
        seenUrls.add(url);

        int currentDocId = docId++;

        Map<String, TokenResult> tokenMap = new HashMap<>();
        // Prefer RSS-provided title (cleaner) over <title> tag (often has site branding)
        String htmlTitle = "";

        for (Node node : docNodes) {
            String text = node.text;
            Tag tag = node.tag;

            if (tag == Tag.TITLE) htmlTitle = text;

            List<String> tokens = Tokenizer.tokenize(text);
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                TokenResult tr = tokenMap.computeIfAbsent(token, k -> new TokenResult());
                tr.tf += 1;
                tr.tagMask |= tag.bit;
            }
            // Bigrams let compound-noun queries ("write-ahead logging", "circuit breaker")
            // beat unigram noise. Requires ~1.5g heap — run via fat JAR to bypass Maven overhead.
            for (String bigram : Tokenizer.getNGrams(tokens, 2)) {
                TokenResult tr = tokenMap.computeIfAbsent(bigram, k -> new TokenResult());
                tr.tf += 1;
                tr.tagMask |= tag.bit;
            }
        }

        for (Map.Entry<String, TokenResult> entry : tokenMap.entrySet()) {
            String term = entry.getKey();
            TokenResult tr = entry.getValue();
            invertedIndex.computeIfAbsent(term, k -> new ArrayList<>())
                    .add(new Posting(currentDocId, tr.tf, tr.tagMask));
        }

        int docLength = tokenMap.values().stream().mapToInt(tr -> tr.tf).sum();
        String title = (rssTitle != null && !rssTitle.isEmpty()) ? rssTitle : htmlTitle;
        docMetadata.put(currentDocId, new BlogDocMeta(url, docLength, title, 0.0, company, postDate));

        // Dense embedding: title + truncated body. The HF tokenizer truncates to 256
        // tokens internally; the char cap is just to bound StringBuilder churn. Failures
        // (network blip, OOM) are logged and the doc is left without an embedding —
        // it'll be unrankable by the dense reranker but BM25 still surfaces it.
        if (embedAvailable) {
            try {
                StringBuilder body = new StringBuilder(embeddingTextCharCap + 256);
                if (title != null) body.append(title).append(". ");
                for (Node node : docNodes) {
                    if (node.tag == Tag.TITLE || node.text == null) continue;
                    body.append(node.text).append(' ');
                    if (body.length() >= embeddingTextCharCap) break;
                }
                float[] vec = BlogReranker.embed(body.toString());
                docEmbeddings.put(currentDocId, vec);
            } catch (Exception e) {
                embeddingsFailed++;
                if (embeddingsFailed <= 3) {
                    System.err.println("WARN: embed failed for doc " + currentDocId
                            + " (" + url + "): " + e.getMessage());
                }
            }
        }
    }

    public static void writeBlogEmbeddings() {
        if (docEmbeddings.isEmpty()) {
            System.err.println("WARN: no embeddings to write, skipping blog_embeddings.bin");
            return;
        }
        int dim = BlogReranker.EMBEDDING_DIM;
        int n = docId; // dense slot count: one row per docId, even if some failed
        // Header: [int32 N][int32 dim], then N * dim float32. Doc i's vector lives
        // at byte offset 8 + i * dim * 4. Missing docs get a zero vector so cosine
        // against them is 0 (sinks to bottom of rerank without special-casing).
        long bytes = 8L + (long) n * dim * 4L;
        try (FileChannel ch = FileChannel.open(Path.of("blog_embeddings.bin"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(8);
            header.putInt(n);
            header.putInt(dim);
            header.flip();
            ch.write(header);

            float[] zero = new float[dim];
            ByteBuffer row = ByteBuffer.allocate(dim * 4);
            for (int i = 0; i < n; i++) {
                float[] v = docEmbeddings.getOrDefault(i, zero);
                row.clear();
                for (float f : v) row.putFloat(f);
                row.flip();
                ch.write(row);
            }
            System.out.printf("Wrote blog_embeddings.bin (%d docs × %d dim, %.1f MB)%n",
                    n, dim, bytes / 1024.0 / 1024.0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
