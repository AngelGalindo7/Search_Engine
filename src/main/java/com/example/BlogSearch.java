package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
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
    public static final double AUTHOR_BOOST = 3.0;
    public static final int    BIGRAM_MIN_DF = 2;

    // Dense reranker: opt-in via -Dsearch.reranker=dense. Default "off" preserves the
    // BM25 + tag-mask + PageRank + diversification baseline. When on, the top
    // RERANK_CANDIDATES from the lexical pipeline are re-sorted by cosine(query, doc)
    // using L2-normalized embeddings from blog_embeddings.bin.
    public static final String RERANKER_MODE = System.getProperty("search.reranker", "off");
    // Pipeline selector for eval/leaderboard: "bm25" | "dense" | "hybrid". BlogServer
    // always uses searchResults() directly; this flag only affects runEval() and the
    // Retriever abstraction.
    public static final String SEARCH_CONFIG = System.getProperty("search.config", "bm25");
    public static final int    RERANK_CANDIDATES = 100;
    private static volatile float[][] docEmbeddingsCache;
    private static FileChannel indexChannel;   // kept open; closing invalidates the mmap on some JVMs
    private static MappedByteBuffer indexMmap;

    // Known author name (lowercase) → registrable domain for query-time domain boost.
    private static final Map<String, String> AUTHOR_DOMAINS = Map.of(
        "dan luu",       "danluu.com",
        "julia evans",   "jvns.ca",
        "marc brooker",  "brooker.co.za",
        "martin fowler", "martinfowler.com",
        "brendan gregg", "brendangregg.com",
        "will larson",   "lethain.com",
        "alex russell",  "infrequently.org"
    );

    // Bidirectional acronym↔expansion pairs; all lowercase, expansion space-separated.
    private static final String[][] SYNONYM_PAIRS = {
        {"wal",  "write ahead logging"},
        {"gil",  "global interpreter lock"},
        {"jvm",  "java virtual machine"},
        {"jit",  "just in time"},
        {"gc",   "garbage collection"},
        {"oom",  "out of memory"},
        {"stw",  "stop the world"},
    };

    private static volatile List<SearchResult> topByPageRankCache;
    private static volatile List<SearchResult> recentPostsCache;
    private static volatile Map<String, Object> statsCache;
    private static volatile Map<Integer, String> tldrCache;

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
        if ("eval".equals(System.getProperty("search.mode"))) {
            loadDependencies();
            System.out.printf("Loaded %d tokens, %d docs, avg doc length %.0f%n",
                    tokenMetadata.size(), TOTAL_DOCS, AVG_DOC_LENGTH);
            try {
                runEval();
            } catch (IOException e) {
                System.err.println("eval failed: " + e.getMessage());
            }
            return;
        }

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

    // Reads eval/queries.json, runs every query, writes eval/results.json.
    // One JVM session for all queries — no repeated Maven startup overhead.
    private static void runEval() throws IOException {
        Path queriesPath = Path.of("eval/queries.json");
        if (!Files.exists(queriesPath)) {
            System.err.println("eval/queries.json not found");
            return;
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonArray queries = JsonParser.parseString(Files.readString(queriesPath)).getAsJsonArray();
        JsonArray output = new JsonArray();

        Retriever retriever = getConfiguredRetriever();
        System.out.println("[eval] pipeline: " + SEARCH_CONFIG);

        for (JsonElement el : queries) {
            JsonObject q = el.getAsJsonObject();
            String queryText = q.get("query").getAsString();
            String category  = q.has("category") ? q.get("category").getAsString() : "";

            List<SearchResult> hits = retriever.search(queryText, 10);

            JsonObject entry = new JsonObject();
            entry.addProperty("query", queryText);
            entry.addProperty("category", category);

            JsonArray resultsArr = new JsonArray();
            for (int i = 0; i < hits.size(); i++) {
                SearchResult r = hits.get(i);
                JsonObject hit = new JsonObject();
                hit.addProperty("rank", i + 1);
                hit.addProperty("score", r.score);
                hit.addProperty("title", r.title);
                hit.addProperty("company", r.company);
                hit.addProperty("url", r.url);
                resultsArr.add(hit);
            }
            entry.add("results", resultsArr);
            output.add(entry);

            System.out.printf("[eval] %-45s -> %d hits%n", queryText, hits.size());
        }

        Files.createDirectories(Path.of("eval"));
        Files.writeString(Path.of("eval/results.json"), gson.toJson(output));
        System.out.println("Written eval/results.json (" + queries.size() + " queries)");

        writeManifest(gson, queries.size());
    }

    // Captures the configuration that produced this run so archived runs are self-describing.
    // Without this, a metric delta between runs is impossible to attribute to a specific change.
    private static void writeManifest(Gson gson, int queryCount) throws IOException {
        JsonObject m = new JsonObject();
        m.addProperty("timestamp", java.time.Instant.now().toString());
        m.addProperty("query_count", queryCount);

        JsonObject git = new JsonObject();
        git.addProperty("commit", runGit("rev-parse", "HEAD"));
        git.addProperty("branch", runGit("rev-parse", "--abbrev-ref", "HEAD"));
        git.addProperty("dirty",  !runGit("status", "--porcelain").isEmpty());
        m.add("git", git);

        JsonObject index = new JsonObject();
        index.addProperty("total_docs",     TOTAL_DOCS);
        index.addProperty("total_tokens",   tokenMetadata.size());
        index.addProperty("avg_doc_length", (int) AVG_DOC_LENGTH);
        m.add("index", index);

        JsonObject ranking = new JsonObject();
        ranking.addProperty("ALPHA",         ALPHA);
        ranking.addProperty("SCALE",         SCALE);
        ranking.addProperty("K1",            K1);
        ranking.addProperty("B",             B);
        ranking.addProperty("DOMAIN_DECAY",  DOMAIN_DECAY);
        ranking.addProperty("AUTHOR_BOOST",  AUTHOR_BOOST);
        ranking.addProperty("BIGRAM_MIN_DF", BIGRAM_MIN_DF);
        ranking.addProperty("SEARCH_CONFIG",  SEARCH_CONFIG);
        ranking.addProperty("RERANKER_MODE", RERANKER_MODE);
        if ("dense".equals(RERANKER_MODE)) {
            ranking.addProperty("RERANK_CANDIDATES", RERANK_CANDIDATES);
            ranking.addProperty("RERANK_MODEL",      BlogReranker.MODEL_URL);
            ranking.addProperty("RERANK_DIM",        BlogReranker.EMBEDDING_DIM);
        }
        m.add("ranking", ranking);

        JsonObject features = new JsonObject();
        features.addProperty("synonym_pairs",  SYNONYM_PAIRS.length);
        features.addProperty("author_domains", AUTHOR_DOMAINS.size());
        features.addProperty("bigrams",        "all-tags");  // matches BlogIndexer scope
        m.add("features", features);

        // Merge in the per-component sub-manifests written by Crawler / BlogIndexer / BlogPageRank.
        // Missing files degrade silently so eval can still run if a component wasn't re-executed.
        mergeSubManifest(m, "crawl",    Path.of("eval/crawl_manifest.json"));
        mergeSubManifest(m, "indexer",  Path.of("eval/index_manifest.json"));
        mergeSubManifest(m, "pagerank", Path.of("eval/pagerank_manifest.json"));

        Files.writeString(Path.of("eval/manifest.json"), gson.toJson(m));
        System.out.println("Written eval/manifest.json");
    }

    private static void mergeSubManifest(JsonObject root, String key, Path file) {
        try {
            if (!Files.exists(file)) return;
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            root.add(key, parsed);
        } catch (Exception e) {
            System.err.println("merge " + file + " failed: " + e.getMessage());
        }
    }

    private static String runGit(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            for (String a : args) cmd.add(a);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static Retriever getConfiguredRetriever() {
        return switch (SEARCH_CONFIG) {
            case "dense"  -> new DenseRetriever();
            case "hybrid" -> new RRFFusion(new BM25Retriever(), new DenseRetriever(), 60);
            default       -> new BM25Retriever(); // "bm25"
        };
    }

    public static void loadDependencies() {
        tokenMetadata = BlogIndexer.readBlogTokenMetadata();
        docMetadata = BlogIndexer.readBlogDocMetadata();
        TOTAL_DOCS = docMetadata.size();
        long totalLen = 0;
        for (BlogDocMeta m : docMetadata.values()) totalLen += m.length;
        AVG_DOC_LENGTH = TOTAL_DOCS == 0 ? 1.0 : (double) totalLen / TOTAL_DOCS;
        loadTldr();
        loadIndexMmap();
    }

    private static void loadTldr() {
        Path path = Path.of("blog_tldr.txt");
        if (!Files.exists(path)) {
            System.err.println("WARN: blog_tldr.txt not found; run BlogTldrExtractor to enable TL;DRs");
            tldrCache = Collections.emptyMap();
            return;
        }
        Map<Integer, String> cache = new HashMap<>();
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 1) continue;
                try {
                    int docId = Integer.parseInt(line.substring(0, tab));
                    String tldr = line.substring(tab + 1);
                    if (!tldr.isBlank()) cache.put(docId, tldr);
                } catch (NumberFormatException ignored) {}
            }
        } catch (IOException e) {
            System.err.println("WARN: could not read blog_tldr.txt: " + e.getMessage());
            tldrCache = Collections.emptyMap();
            return;
        }
        tldrCache = Collections.unmodifiableMap(cache);
        System.out.printf("Loaded %d TL;DRs from blog_tldr.txt%n", tldrCache.size());
    }

    public static List<SearchResult> topByPageRank(int n) {
        if (topByPageRankCache == null) {
            synchronized (BlogSearch.class) {
                if (topByPageRankCache == null) {
                    List<Map.Entry<Integer, BlogDocMeta>> entries = new ArrayList<>(docMetadata.entrySet());
                    entries.sort((a, b) -> Double.compare(b.getValue().pageRank, a.getValue().pageRank));
                    List<SearchResult> out = new ArrayList<>(Math.min(50, entries.size()));
                    for (int i = 0; i < Math.min(50, entries.size()); i++) {
                        int docId = entries.get(i).getKey();
                        BlogDocMeta m = entries.get(i).getValue();
                        String tldr = tldrCache != null ? tldrCache.get(docId) : null;
                        out.add(new SearchResult(docId, m.pageRank, m.title, m.company, m.url,
                                tldr, null, null, m.postDate));
                    }
                    topByPageRankCache = Collections.unmodifiableList(out);
                }
            }
        }
        int top = Math.min(Math.max(1, n), topByPageRankCache.size());
        return topByPageRankCache.subList(0, top);
    }

    public static List<SearchResult> recentPosts(int n) {
        if (recentPostsCache == null) {
            synchronized (BlogSearch.class) {
                if (recentPostsCache == null) {
                    String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
                    List<Map.Entry<Integer, BlogDocMeta>> entries = new ArrayList<>(docMetadata.entrySet());
                    entries.removeIf(e -> {
                        String d = e.getValue().postDate;
                        if (d == null || d.isBlank()) return true;
                        String day = d.length() >= 10 ? d.substring(0, 10) : d;
                        return day.compareTo(today) > 0;
                    });
                    entries.sort((a, b) -> b.getValue().postDate.compareTo(a.getValue().postDate));
                    int limit = Math.min(50, entries.size());
                    List<SearchResult> out = new ArrayList<>(limit);
                    for (int i = 0; i < limit; i++) {
                        int docId = entries.get(i).getKey();
                        BlogDocMeta m = entries.get(i).getValue();
                        String tldr = tldrCache != null ? tldrCache.get(docId) : null;
                        out.add(new SearchResult(docId, 0.0, m.title, m.company, m.url,
                                tldr, null, null, m.postDate));
                    }
                    recentPostsCache = Collections.unmodifiableList(out);
                }
            }
        }
        int top = Math.min(Math.max(1, n), recentPostsCache.size());
        return recentPostsCache.subList(0, top);
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

    // Core BM25 + tag-mask + PageRank + bigrams + synonyms + author boost + domain diversification.
    // Does not apply dense rerank — call searchResults() for the full pipeline including rerank.
    static List<SearchResult> bm25Search(String query, int topN) {
        return bm25Search(query, topN, false);
    }

    static List<SearchResult> bm25Search(String query, int topN, boolean explain) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        // Expand acronyms bidirectionally, then add bigrams so compound-noun
        // queries ("write-ahead logging") can match phrase tokens in the index.
        // List<String> queryTokens = Tokenizer.tokenize(query);
        List<String> queryTokens = new ArrayList<>(Tokenizer.tokenize(expandSynonyms(query)));
        queryTokens.addAll(Tokenizer.getNGrams(new ArrayList<>(queryTokens), 2));
        Map<String, List<Posting>> queryIndex = new HashMap<>();
        for (String token : queryTokens) {
            List<Posting> postings = getTokenPostings(token);
            queryIndex.put(token, postings == null ? Collections.emptyList() : postings);
        }

        Map<Integer, Double> docRank = new HashMap<>();
        // explain captures: raw BM25 sum (before prBoost) and the per-doc prBoost value.
        Map<Integer, Double> explainBm25   = explain ? new HashMap<>() : null;
        Map<Integer, Double> explainPrMult = explain ? new HashMap<>() : null;
        for (Map.Entry<String, List<Posting>> entry : queryIndex.entrySet()) {
            String token = entry.getKey();
            TokenMeta tm = tokenMetadata.get(token);
            if (tm == null) continue;
            // Skip rare bigrams: their max-IDF dominates BM25 and surfaces marginal docs
            // (one matching phrase in a heading) over docs with strong unigram coverage.
            if (token.indexOf(' ') >= 0 && tm.df < BIGRAM_MIN_DF) continue;
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
                if (explain) {
                    explainBm25.merge(p.docId, relevance, Double::sum);
                    // prBoost is doc-fixed; last write wins (all postings produce same value).
                    explainPrMult.put(p.docId, prBoost);
                }
            }
        }

        // Lift scores for posts from a known author's domain when their name appears in the query.
        String authorDomain = detectAuthorDomain(query);
        if (authorDomain != null) {
            String ad = authorDomain;
            docRank.replaceAll((id, score) -> {
                BlogDocMeta m = docMetadata.get(id);
                return m != null && registrableDomain(m.url).equals(ad) ? score * AUTHOR_BOOST : score;
            });
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
            String tldr = tldrCache != null ? tldrCache.get(docId) : null;
            if (explain) {
                out.add(new SearchResult(docId, diversifiedRank.get(docId), m.title, m.company, m.url,
                        tldr, explainBm25.getOrDefault(docId, 0.0),
                        explainPrMult.getOrDefault(docId, 0.0), m.postDate));
            } else {
                out.add(new SearchResult(docId, diversifiedRank.get(docId), m.title, m.company, m.url,
                        tldr, null, null, m.postDate));
            }
        }
        return out;
    }

    public static List<SearchResult> searchResults(String query, int topN) {
        return searchResults(query, topN, false);
    }

    public static List<SearchResult> searchResults(String query, int topN, boolean explain) {
        // bm25Search already covers BM25 + tag-mask + PageRank + bigrams + synonyms + diversification.
        // Dense rerank is a post-processing step layered on top: re-sort the top-K prefix by cosine.
        List<SearchResult> results = bm25Search(query, topN, explain);
        if (!"dense".equals(RERANKER_MODE) || results.isEmpty()) return results;

        // Dense rerank: re-sort the top-K prefix of results by cosine(query, doc).
        // The tail (positions K..end) keeps its BM25-order, which only matters for
        // queries with > K candidates -- those tail docs aren't visible at top-N.
        float[][] docEmb = loadDocEmbeddings();
        if (docEmb.length == 0) return results;
        float[] queryEmb;
        try {
            queryEmb = BlogReranker.embed(query);
        } catch (Exception e) {
            System.err.println("dense rerank: query embed failed: " + e.getMessage());
            return results;
        }
        int k = Math.min(RERANK_CANDIDATES, results.size());
        List<SearchResult> head = new ArrayList<>(results.subList(0, k));
        List<SearchResult> tail = results.subList(k, results.size());
        head.sort(Comparator.comparingDouble((SearchResult r) ->
                r.docId < docEmb.length ? BlogReranker.cosine(queryEmb, docEmb[r.docId]) : 0.0
        ).reversed());
        List<SearchResult> out = new ArrayList<>(results.size());
        // Rebuild with cosine as the score for the reranked prefix; carry explain fields through.
        for (SearchResult r : head) {
            double cos = r.docId < docEmb.length ? BlogReranker.cosine(queryEmb, docEmb[r.docId]) : 0.0;
            out.add(new SearchResult(r.docId, cos, r.title, r.company, r.url,
                    r.tldr, r.bm25Score, r.pageRankMultiplier, r.postDate));
        }
        out.addAll(tail);
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

    // True when word appears at a word boundary (not inside a longer alphanumeric run).
    private static boolean containsWord(String text, String word) {
        int i = text.indexOf(word);
        while (i >= 0) {
            boolean before = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            boolean after  = i + word.length() == text.length()
                             || !Character.isLetterOrDigit(text.charAt(i + word.length()));
            if (before && after) return true;
            i = text.indexOf(word, i + 1);
        }
        return false;
    }

    // Append the counterpart form (acronym or expansion) when only one side is present.
    private static String expandSynonyms(String query) {
        String lower = query.toLowerCase();
        StringBuilder extra = new StringBuilder();
        for (String[] pair : SYNONYM_PAIRS) {
            String abbr = pair[0], full = pair[1];
            boolean hasAbbr = containsWord(lower, abbr);
            boolean hasFull = lower.contains(full);
            if (hasAbbr && !hasFull) extra.append(" ").append(full);
            else if (hasFull && !hasAbbr) extra.append(" ").append(abbr);
        }
        return extra.length() == 0 ? query : query + extra;
    }

    // Returns the registrable domain to boost if the query names a known author, else null.
    private static String detectAuthorDomain(String query) {
        String lower = query.toLowerCase();
        for (Map.Entry<String, String> e : AUTHOR_DOMAINS.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public static double getTagMultiplier(int tagMask) {
        if ((tagMask & Tag.TITLE.bit) != 0) return 3.0;
        if ((tagMask & Tag.HEADING.bit) != 0) return 2.0;
        if ((tagMask & Tag.ANCHOR.bit) != 0) return 2.0;
        if ((tagMask & Tag.EMPHASIS.bit) != 0) return 1.5;
        if ((tagMask & Tag.BODY.bit) != 0) return 1.0;
        return 1.0;
    }

    private static float[][] loadDocEmbeddings() {
        if (docEmbeddingsCache != null) return docEmbeddingsCache;
        synchronized (BlogSearch.class) {
            if (docEmbeddingsCache != null) return docEmbeddingsCache;
            Path path = Path.of("blog_embeddings.bin");
            if (!Files.exists(path)) {
                System.err.println("WARN: blog_embeddings.bin not found; dense rerank is a no-op");
                docEmbeddingsCache = new float[0][];
                return docEmbeddingsCache;
            }
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
                ByteBuffer header = ByteBuffer.allocate(8);
                while (header.hasRemaining()) if (ch.read(header) < 0) break;
                header.flip();
                int n = header.getInt();
                int dim = header.getInt();
                float[][] mat = new float[n][dim];
                ByteBuffer row = ByteBuffer.allocate(dim * 4);
                for (int i = 0; i < n; i++) {
                    row.clear();
                    while (row.hasRemaining()) if (ch.read(row) < 0) break;
                    row.flip();
                    for (int j = 0; j < dim; j++) mat[i][j] = row.getFloat();
                }
                docEmbeddingsCache = mat;
                System.out.printf("Loaded %d × %d embeddings from blog_embeddings.bin%n", n, dim);
            } catch (IOException e) {
                e.printStackTrace();
                docEmbeddingsCache = new float[0][];
            }
            return docEmbeddingsCache;
        }
    }

    public static List<SearchResult> findNeighbors(int docId, int k, String excludeCompany) {
        float[][] emb = loadDocEmbeddings();
        if (emb.length == 0 || docId < 0 || docId >= emb.length) return List.of();
        float[] qVec = emb[docId];
        BlogDocMeta qMeta = docMetadata.get(docId);
        // min-heap: keep top-k by cosine (highest score = best neighbor)
        PriorityQueue<double[]> heap = new PriorityQueue<>(k + 1,
                Comparator.comparingDouble(a -> a[0]));
        for (int i = 0; i < emb.length; i++) {
            if (i == docId) continue;
            BlogDocMeta m = docMetadata.get(i);
            if (m == null) continue;
            if (excludeCompany != null && excludeCompany.equalsIgnoreCase(m.company)) continue;
            double cos = BlogReranker.cosine(qVec, emb[i]);
            heap.offer(new double[]{cos, i});
            if (heap.size() > k) heap.poll();
        }
        List<double[]> sorted = new ArrayList<>(heap);
        sorted.sort(Comparator.comparingDouble((double[] a) -> a[0]).reversed());
        List<SearchResult> out = new ArrayList<>(sorted.size());
        for (double[] pair : sorted) {
            int nId = (int) pair[1];
            BlogDocMeta m = docMetadata.get(nId);
            if (m == null) continue;
            out.add(new SearchResult(nId, pair[0], m.title, m.company, m.url,
                    tldrCache != null ? tldrCache.get(nId) : null));
        }
        return out;
    }

    private static void loadIndexMmap() {
        Path path = Path.of("blog_index.bin");
        if (!Files.exists(path)) {
            System.err.println("WARN: blog_index.bin not found; queries will return no results");
            return;
        }
        try {
            indexChannel = FileChannel.open(path, StandardOpenOption.READ);
            long size = indexChannel.size();
            indexMmap = indexChannel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            indexMmap.load();  // eagerly fault in all pages; one-time ~2-3s startup cost, eliminates page-fault variance on queries
            System.out.printf("Mapped and loaded blog_index.bin (%d MB) into page cache%n",
                    size / (1024 * 1024));
        } catch (IOException e) {
            System.err.println("WARN: could not mmap blog_index.bin: " + e.getMessage());
        }
    }

    public static List<Posting> getTokenPostings(String token) {
        TokenMeta tm = tokenMetadata.get(token);
        if (tm == null) return null;
        int df = tm.df;
        long offset = tm.offset;
        int length = tm.length;

        // Was: FileChannel.open("blog_index.bin") inside a try-with-resources on every call.
        // That caused O(T) file opens per query (T = tokens after bigram expansion, typically 9-15),
        // each paying open + seek on a 248 MB file — 200-250 ms of the 444 ms median latency.
        // Now: single READ_ONLY mmap at startup; duplicate() gives each caller an independent
        // position/limit on the shared mapping. Thread-safe for concurrent reads.
        List<Posting> postings = new ArrayList<>(df);
        if (indexMmap == null) return postings;  // mmap failed at startup; warning already printed
        ByteBuffer buf = indexMmap.duplicate();
        buf.position((int) offset).limit((int) offset + length);
        // read only complete postings — silently skips trailing partial bytes
        // that result from a stale token_meta pointing into a mismatched index file.
        while (buf.remaining() >= 12) {
            int docId   = buf.getInt();
            int tf      = buf.getInt();
            int tagMask = buf.getInt();
            postings.add(new Posting(docId, tf, tagMask));
        }
        return postings;
    }
}
