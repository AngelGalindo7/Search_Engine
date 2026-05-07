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
    public static final int    BIGRAM_MIN_DF = 3;

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

        for (JsonElement el : queries) {
            JsonObject q = el.getAsJsonObject();
            String queryText = q.get("query").getAsString();
            String category  = q.has("category") ? q.get("category").getAsString() : "";

            List<SearchResult> hits = searchResults(queryText, 10);

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
        m.add("ranking", ranking);

        JsonObject features = new JsonObject();
        features.addProperty("synonym_pairs",  SYNONYM_PAIRS.length);
        features.addProperty("author_domains", AUTHOR_DOMAINS.size());
        features.addProperty("bigrams",        "all-tags");  // matches BlogIndexer scope
        m.add("features", features);

        Files.writeString(Path.of("eval/manifest.json"), gson.toJson(m));
        System.out.println("Written eval/manifest.json");
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

    public static List<Posting> getTokenPostings(String token) {
        TokenMeta tm = tokenMetadata.get(token);
        if (tm == null) return null;
        int df = tm.df;
        long offset = tm.offset;
        int length = tm.length;

        List<Posting> postings = new ArrayList<>(df);
        try (FileChannel channel = FileChannel.open(Path.of("blog_index.bin"), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            // FileChannel.read is not guaranteed to fill the buffer in one call;
            // loop until full or EOF to guard against partial reads and index/metadata skew.
            long pos = offset;
            while (buf.hasRemaining()) {
                int n = channel.read(buf, pos);
                if (n <= 0) break;
                pos += n;
            }
            buf.flip();
            // read only complete postings — silently skips trailing partial bytes
            // that result from a stale token_meta pointing into a mismatched index file.
            while (buf.remaining() >= 12) {
                int docId   = buf.getInt();
                int tf      = buf.getInt();
                int tagMask = buf.getInt();
                postings.add(new Posting(docId, tf, tagMask));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return postings;
    }
}
