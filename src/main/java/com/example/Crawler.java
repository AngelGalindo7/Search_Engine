package com.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

public class Crawler {

    private static final String USER_AGENT = "SearchEngineProject/1.0";
    private static final long MIN_DOMAIN_DELAY_MS = 1000;
    private static final String OUT_DIR = "BLOGS";

    private static final Map<String, Long> lastFetchByDomain = new HashMap<>();
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private static long totalBytesWritten = 0;
    // Filename stems (sha1[0:16] of url) of every JSON already on disk across ALL company dirs.
    // Lets us skip a fetch when a URL has been crawled under a different company name (e.g. a
    // republished post in two companies' RSS), not just when it's already in the same dir.
    private static final Set<String> globalUrlHashes = new HashSet<>();
    private static int crossCompanyDuplicates = 0;

    public static List<BlogSource> loadBlogList(String opmlPath) throws Exception {
        List<BlogSource> blogs = new ArrayList<>();
        DocumentBuilder builder = SecurityGuards.secureXmlBuilder();
        org.w3c.dom.Document doc = builder.parse(new File(opmlPath));

        NodeList outlines = doc.getElementsByTagName("outline");
        for (int i = 0; i < outlines.getLength(); i++) {
            Element el = (Element) outlines.item(i);
            String xmlUrl = el.getAttribute("xmlUrl");
            if (xmlUrl == null || xmlUrl.isEmpty()) continue;
            String name = el.getAttribute("title");
            if (name == null || name.isEmpty()) name = el.getAttribute("text");
            String htmlUrl = el.getAttribute("htmlUrl");
            blogs.add(new BlogSource(name, xmlUrl, htmlUrl));
        }
        return blogs;
    }

    public static void main(String[] args) throws Exception {
        long crawlStartMs = System.currentTimeMillis();
        int blogLimit = args.length > 0 ? Integer.parseInt(args[0]) : Integer.MAX_VALUE;
        int postsPerBlogLimit = args.length > 1 ? Integer.parseInt(args[1]) : Integer.MAX_VALUE;

        // List<BlogSource> blogs = loadBlogList("engineering_blogs.opml");
        List<BlogSource> allBlogs = loadBlogList("engineering_blogs.opml");
        int feedsTotal = allBlogs.size();
        // Skip Medium-hosted feeds (ToS-restrictive + aggressive throttling)
        List<BlogSource> blogs = new ArrayList<>(allBlogs);
        blogs.removeIf(b -> b.rssUrl != null && b.rssUrl.contains("medium.com/feed"));
        int feedsSkippedMedium = feedsTotal - blogs.size();

        Files.createDirectories(Path.of(OUT_DIR));
        loadGlobalUrlHashes();
        System.out.println("Pre-loaded " + globalUrlHashes.size() + " existing URL hashes from BLOGS/");

        int totalPosts = 0;
        int totalSkipped = 0;
        int totalFailed = 0;
        int totalExisting = 0;
        int blogsProcessed = 0;

        for (BlogSource blog : blogs) {
            if (blogsProcessed++ >= blogLimit) break;
            int[] result = crawlBlog(blog, postsPerBlogLimit);
            totalPosts += result[0];
            totalSkipped += result[1];
            totalFailed += result[2];
            totalExisting += result[3];
            System.out.printf("[%d/%d] %-30s posts=%d existing=%d skipped=%d failed=%d%n",
                    blogsProcessed, Math.min(blogs.size(), blogLimit),
                    truncate(blog.name, 30), result[0], result[3], result[1], result[2]);
        }

        long durationSec = (System.currentTimeMillis() - crawlStartMs) / 1000;

        System.out.println("---");
        System.out.println("Total posts written: " + totalPosts);
        // System.out.println("Total existing (already on disk): " + totalExisting);
        System.out.println("Total existing (already on disk): " + totalExisting
                + "  (of which " + crossCompanyDuplicates + " were cross-company duplicates skipped)");
        System.out.println("Total skipped (robots/url-unsafe): " + totalSkipped);
        System.out.println("Total failed (network/parse): " + totalFailed);

        // blogsProcessed is post-increment; subtract 1 if loop hit the limit, else clamp to feed count
        int crawledCount = Math.min(blogsProcessed > blogLimit ? blogLimit : blogsProcessed, blogs.size());
        writeCrawlManifest(durationSec, feedsTotal, feedsSkippedMedium, blogs.size(),
                crawledCount,
                totalPosts, totalExisting, totalSkipped, totalFailed,
                blogLimit, postsPerBlogLimit);
    }

    // Captures crawl-side provenance so a future eval delta is attributable to a corpus change
    // (more feeds, more posts) rather than guessed at. Read by BlogSearch.writeManifest at eval time.
    private static void writeCrawlManifest(long durationSec, int feedsTotal, int feedsSkippedMedium,
                                           int feedsAttempted, int blogsProcessed,
                                           int postsWritten, int postsExisting,
                                           int postsSkipped, int postsFailed,
                                           int blogLimit, int postsPerBlogLimit) {
        try {
            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("timestamp", java.time.Instant.now().toString());
            m.addProperty("duration_sec", durationSec);
            m.addProperty("opml_sha256", sha256File("engineering_blogs.opml"));
            m.addProperty("feeds_total", feedsTotal);
            m.addProperty("feeds_skipped_medium", feedsSkippedMedium);
            m.addProperty("feeds_attempted", feedsAttempted);
            m.addProperty("blogs_processed", blogsProcessed);
            m.addProperty("posts_written", postsWritten);
            m.addProperty("posts_existing", postsExisting);
            m.addProperty("cross_company_duplicates_skipped", crossCompanyDuplicates);
            m.addProperty("posts_skipped", postsSkipped);
            m.addProperty("posts_failed", postsFailed);
            m.addProperty("total_bytes_written", totalBytesWritten);
            m.addProperty("user_agent", USER_AGENT);
            m.addProperty("min_domain_delay_ms", MIN_DOMAIN_DELAY_MS);
            m.addProperty("max_total_output_bytes", SecurityGuards.MAX_TOTAL_OUTPUT_BYTES);
            m.addProperty("max_posts_per_blog_hard", SecurityGuards.MAX_POSTS_PER_BLOG_HARD);
            m.addProperty("blog_limit_arg", blogLimit == Integer.MAX_VALUE ? -1 : blogLimit);
            m.addProperty("posts_per_blog_limit_arg", postsPerBlogLimit == Integer.MAX_VALUE ? -1 : postsPerBlogLimit);
            Files.createDirectories(Path.of("eval"));
            Files.writeString(Path.of("eval/crawl_manifest.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(m));
            System.out.println("Written eval/crawl_manifest.json");
        } catch (Exception e) {
            System.err.println("crawl manifest write failed: " + e.getMessage());
        }
    }

    private static void loadGlobalUrlHashes() {
        Path root = Path.of(OUT_DIR);
        if (!Files.isDirectory(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root, 2)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .map(p -> p.getFileName().toString())
                  .map(n -> n.substring(0, n.length() - 5))   // strip .json
                  .forEach(globalUrlHashes::add);
        } catch (Exception e) {
            System.err.println("loadGlobalUrlHashes failed: " + e.getMessage());
        }
    }

    private static String sha256File(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(Files.readAllBytes(Path.of(path)));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static int[] crawlBlog(BlogSource blog, int postLimit) {
        int written = 0, skipped = 0, failed = 0, existing = 0;

        if (!SecurityGuards.isUrlSafe(blog.rssUrl)) {
            System.err.printf("  [SKIP-FEED] %s -> unsafe rss url: %s%n", blog.name, blog.rssUrl);
            return new int[]{0, 1, 0, 0};
        }

        SyndFeed feed;
        try {
            URL feedUrl = new URL(blog.rssUrl);
            rateLimit(feedUrl.getHost());
            SyndFeedInput input = new SyndFeedInput();
            input.setAllowDoctypes(false);
            // feed = input.build(new XmlReader(feedUrl));
            URLConnection conn = feedUrl.openConnection();
            conn.setConnectTimeout(SecurityGuards.FEED_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(SecurityGuards.FEED_READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            feed = input.build(new XmlReader(conn));
        } catch (Exception e) {
            System.err.printf("  [FEED-FAIL] %s -> %s: %s [%s]%n",
                    blog.name, e.getClass().getSimpleName(), trimMsg(e.getMessage()), blog.rssUrl);
            return new int[]{0, 0, 1, 0};
        }

        String companySlug = slug(blog.name);
        Path companyDir = Path.of(OUT_DIR, companySlug);
        try {
            Files.createDirectories(companyDir);
        } catch (Exception e) {
            System.err.printf("  [DIR-FAIL] %s -> %s: %s%n",
                    blog.name, e.getClass().getSimpleName(), trimMsg(e.getMessage()));
            return new int[]{0, 0, 1, 0};
        }

        Map<String, Integer> postFailReasons = new LinkedHashMap<>();
        int processed = 0;
        int hardCap = Math.min(postLimit, SecurityGuards.MAX_POSTS_PER_BLOG_HARD);
        for (SyndEntry entry : feed.getEntries()) {
            if (processed++ >= hardCap) break;
            if (totalBytesWritten >= SecurityGuards.MAX_TOTAL_OUTPUT_BYTES) {
                System.err.println("ABORT: hit global output cap of " + SecurityGuards.MAX_TOTAL_OUTPUT_BYTES + " bytes");
                break;
            }

            String link = entry.getLink();
            if (link == null || link.isEmpty()) {
                postFailReasons.merge("no-link", 1, Integer::sum);
                continue;
            }
            if (!SecurityGuards.isUrlSafe(link)) {
                postFailReasons.merge("unsafe-url", 1, Integer::sum);
                skipped++;
                continue;
            }

            String hash = sha1(link).substring(0, 16);
            Path file = companyDir.resolve(hash + ".json");
            if (Files.exists(file)) {
                existing++;
                continue;
            }
            // Cross-company dedup: same URL already crawled under a different company dir.
            // The indexer would dedupe at index time anyway, but we save the fetch + disk write here.
            if (globalUrlHashes.contains(hash)) {
                crossCompanyDuplicates++;
                existing++;
                continue;
            }

            if (!RobotsCheck.isAllowed(link, USER_AGENT)) {
                postFailReasons.merge("robots-disallow", 1, Integer::sum);
                skipped++;
                continue;
            }

            try {
                URL u = new URL(link);
                rateLimit(u.getHost());

                String html = SecurityGuards.fetchHtmlSafe(link, USER_AGENT);
                if (html == null || html.isEmpty()) {
                    postFailReasons.merge("empty-html", 1, Integer::sum);
                    failed++;
                    continue;
                }

                Map<String, String> doc = new LinkedHashMap<>();
                doc.put("url", link);
                doc.put("content", html);
                doc.put("title", entry.getTitle() == null ? "" : entry.getTitle());
                doc.put("company", blog.name);
                doc.put("date", entry.getPublishedDate() == null ? "" : entry.getPublishedDate().toInstant().toString());

                try (FileWriter w = new FileWriter(file.toFile())) {
                    gson.toJson(doc, w);
                }
                totalBytesWritten += Files.size(file);
                globalUrlHashes.add(hash);
                written++;
            } catch (Exception e) {
                postFailReasons.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                failed++;
            }
        }

        if (!postFailReasons.isEmpty()) {
            System.err.printf("  [POST-ISSUES] %s: %s%n", blog.name, postFailReasons);
        }

        return new int[]{written, skipped, failed, existing};
    }

    private static String trimMsg(String m) {
        if (m == null) return "(no message)";
        m = m.replaceAll("\\s+", " ").trim();
        return m.length() > 140 ? m.substring(0, 140) + "..." : m;
    }

    private static void rateLimit(String host) {
        long now = System.currentTimeMillis();
        Long last = lastFetchByDomain.get(host);
        if (last != null) {
            long wait = MIN_DOMAIN_DELAY_MS - (now - last);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException ignored) {}
            }
        }
        lastFetchByDomain.put(host, System.currentTimeMillis());
    }

    private static String slug(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] bytes = md.digest(s.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "0".repeat(40);
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
