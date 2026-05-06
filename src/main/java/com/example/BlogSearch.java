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

    public static final double ALPHA = 0.5;
    public static final double SCALE = 1_000_000;
    public static final double K1 = 1.2;
    public static final double B = 0.75;
    public static final double DOMAIN_DECAY = 0.85;

    private static volatile List<SearchResult> topByPageRankCache;
    private static volatile Map<String, Object> statsCache;

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

    public static List<SearchResult> topByPageRank(int n) {
        if (topByPageRankCache == null) {
            synchronized (BlogSearch.class) {
                if (topByPageRankCache == null) {
                    List<Map.Entry<Integer, BlogDocMeta>> entries = new ArrayList<>(docMetadata.entrySet());
                    entries.sort((a, b) -> Double.compare(b.getValue().pageRank, a.getValue().pageRank));
                    List<SearchResult> out = new ArrayList<>(Math.min(50, entries.size()));
                    for (int i = 0; i < Math.min(50, entries.size()); i++) {
                        BlogDocMeta m = entries.get(i).getValue();
                        out.add(new SearchResult(entries.get(i).getKey(), m.pageRank, m.title, m.company, m.url));
                    }
                    topByPageRankCache = Collections.unmodifiableList(out);
                }
            }
        }
        int top = Math.min(Math.max(1, n), topByPageRankCache.size());
        return topByPageRankCache.subList(0, top);
    }

    public static Map<String, Object> stats() {
        if (statsCache == null) {
            synchronized (BlogSearch.class) {
                if (statsCache == null) {
                    // Clamp postDate to today — some RSS feeds publish future-dated posts
                    // (scheduled drafts, misconfigured CMS clocks). Showing them in the stats
                    // strip looks like a bug to users.
                    String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                    Set<String> companies = new HashSet<>();
                    String latestPostDate = "";
                    for (BlogDocMeta m : docMetadata.values()) {
                        if (m.company != null && !m.company.isBlank()) companies.add(m.company);
                        if (m.postDate == null || m.postDate.isBlank()) continue;
                        String dayPart = m.postDate.length() >= 10 ? m.postDate.substring(0, 10) : m.postDate;
                        if (dayPart.compareTo(today) > 0) continue;
                        if (m.postDate.compareTo(latestPostDate) > 0) {
                            latestPostDate = m.postDate;
                        }
                    }
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("totalDocs", TOTAL_DOCS);
                    s.put("totalTokens", tokenMetadata.size());
                    s.put("totalBlogs", companies.size());
                    s.put("avgDocLength", (int) AVG_DOC_LENGTH);
                    s.put("latestPostDate", latestPostDate);
                    statsCache = Collections.unmodifiableMap(s);
                }
            }
        }
        return statsCache;
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
            // Buggy: the +0.5 ended up added to the quotient, not the denominator.
            // double idf = Math.log(((TOTAL_DOCS - df + 0.5) / df + 0.5) + 1);
            double idf = Math.log((TOTAL_DOCS - df + 0.5) / (df + 0.5) + 1);

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
