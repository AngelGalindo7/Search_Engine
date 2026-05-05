package com.example;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class BlogSearch {

    public static Map<String, TokenMeta> tokenMetadata;
    public static Map<Integer, BlogDocMeta> docMetadata;
    public static int TOTAL_DOCS;
    public static double AVG_DOC_LENGTH;

    private static final double ALPHA = 0.5;
    private static final double SCALE = 1_000_000;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final double DOMAIN_DECAY = 0.85;

    private static final String[] DEFAULT_EVAL_QUERIES = {
            "kubernetes networking",
            "rust async",
            "react performance",
            "bm25 ranking",
            "raft consensus",
            "machine learning training",
            "tcp connection",
            "garbage collection",
            "database sharding",
            "distributed tracing"
    };

    public static void main(String[] args) {
        loadDependencies();
        System.out.printf("Loaded %d tokens, %d docs, avg doc length %.0f%n",
                tokenMetadata.size(), TOTAL_DOCS, AVG_DOC_LENGTH);

        String[] queries = args.length > 0
                ? new String[]{ String.join(" ", args) }
                : DEFAULT_EVAL_QUERIES;

        for (String q : queries) {
            System.out.println("\n=== Query: \"" + q + "\" ===");
            long start = System.nanoTime();
            search(q);
            long end = System.nanoTime();
            System.out.printf("(%d ms)%n", (end - start) / 1_000_000);
        }
    }

    public static void loadDependencies() {
        tokenMetadata = BlogIndexer.readBlogTokenMetadata();
        docMetadata = BlogIndexer.readBlogDocMetadata();
        TOTAL_DOCS = docMetadata.size();
        long totalLen = 0;
        for (BlogDocMeta m : docMetadata.values()) totalLen += m.length;
        AVG_DOC_LENGTH = TOTAL_DOCS == 0 ? 1.0 : (double) totalLen / TOTAL_DOCS;
    }

    public static List<SearchResult> searchResults(String query, int topN) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        List<String> queryTokens = Tokenizer.tokenize(query);
        Map<String, List<Posting>> queryIndex = new HashMap<>();
        for (String token : queryTokens) {
            List<Posting> postings = getTokenPostings(token);
            queryIndex.put(token, postings == null ? Collections.emptyList() : postings);
        }

        Map<Integer, Double> docRank = new HashMap<>();
        for (Map.Entry<String, List<Posting>> entry : queryIndex.entrySet()) {
            String token = entry.getKey();
            TokenMeta tm = tokenMetadata.get(token);
            if (tm == null) continue;
            int df = tm.df;
            double idf = Math.log(((TOTAL_DOCS - df + 0.5) / df + 0.5) + 1);

            for (Posting p : entry.getValue()) {
                BlogDocMeta meta = docMetadata.get(p.docId);
                if (meta == null) continue;

                double tf = p.tf;
                int docLength = meta.length;
                double num = tf * (K1 + 1);
                double denom = tf + K1 * (1 - B + B * (docLength / AVG_DOC_LENGTH));
                double bm25 = (num / denom) * idf;
                double tagMult = getTagMultiplier(p.tagMask);
                double relevance = bm25 * tagMult;

                double prBoost = ALPHA * Math.max(0, Math.log10(meta.pageRank * SCALE + 1e-9));
                double finalScore = relevance + prBoost;

                docRank.merge(p.docId, finalScore, Double::sum);
            }
        }

        if (docRank.isEmpty()) return Collections.emptyList();

        List<Integer> ranked = new ArrayList<>(docRank.keySet());
        ranked.sort(Comparator.comparing(docRank::get).reversed());

        // Domain diversification: walk the ranked list in score order and apply 0.85^k decay
        // where k is the count of prior same-domain results. Re-sort by decayed score so the
        // first hit per domain rises and subsequent same-domain hits drop.
        Map<String, Integer> domainSeen = new HashMap<>();
        Map<Integer, Double> diversifiedRank = new HashMap<>();
        for (int docId : ranked) {
            BlogDocMeta m = docMetadata.get(docId);
            String domain = registrableDomain(m.url);
            int k = domainSeen.getOrDefault(domain, 0);
            diversifiedRank.put(docId, docRank.get(docId) * Math.pow(DOMAIN_DECAY, k));
            domainSeen.put(domain, k + 1);
        }
        ranked.sort(Comparator.comparing(diversifiedRank::get).reversed());

        int top = Math.min(topN, ranked.size());
        List<SearchResult> out = new ArrayList<>(top);
        for (int i = 0; i < top; i++) {
            int docId = ranked.get(i);
            BlogDocMeta m = docMetadata.get(docId);
            out.add(new SearchResult(docId, diversifiedRank.get(docId), m.title, m.company, m.url));
        }
        return out;
    }

    public static void search(String query) {
        List<SearchResult> results = searchResults(query, 10);
        if (results.isEmpty()) {
            System.out.println("No results.");
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            System.out.printf("%2d. [%.3f] %s — %s%n      %s%n",
                    i + 1, r.score, r.company, r.title, r.url);
        }
    }

    private static String registrableDomain(String url) {
        if (url == null) return "";
        try {
            String host = new URI(url).getHost();
            if (host == null) return "";
            host = host.toLowerCase();
            String[] parts = host.split("\\.");
            if (parts.length <= 2) return host;
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        } catch (Exception e) {
            return "";
        }
    }

    public static double getTagMultiplier(int tagMask) {
        if ((tagMask & Tag.TITLE.bit) != 0) return 3.0;
        if ((tagMask & Tag.HEADING.bit) != 0) return 2.0;
        if ((tagMask & Tag.ANCHOR.bit) != 0) return 2.0;
        if ((tagMask & Tag.EMPHASIS.bit) != 0) return 1.5;
        if ((tagMask & Tag.BODY.bit) != 0) return 1.0;
        return 1.0;
    }

    public static List<Posting> getTokenPostings(String token) {
        TokenMeta tm = tokenMetadata.get(token);
        if (tm == null) return null;
        int df = tm.df;
        long offset = tm.offset;
        int length = tm.length;

        List<Posting> postings = new ArrayList<>(df);
        try (FileChannel channel = FileChannel.open(Path.of("blog_index.bin"), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            channel.read(buf, offset);
            buf.flip();
            for (int i = 0; i < df; i++) {
                int docId = buf.getInt();
                int tf = buf.getInt();
                int tagMask = buf.getInt();
                postings.add(new Posting(docId, tf, tagMask));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return postings;
    }
}
