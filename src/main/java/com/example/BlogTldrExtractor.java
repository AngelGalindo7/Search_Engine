package com.example;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Offline pipeline step: reads BLOGS/**\/*.json, extracts a lede-skipping
 * extractive TL;DR per document, and writes blog_tldr.txt ({docId}\t{tldr}).
 *
 * Run after BlogIndexer (blog_doc_meta.txt must exist).
 * Output is gitignored; BlogSearch loads it at startup if present.
 */
public class BlogTldrExtractor {

    // Sentence boundary: punctuation followed by whitespace and capital letter.
    private static final Pattern SENTENCE_SPLIT =
            Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");

    // Lede openers to skip — boilerplate framing, not content.
    private static final Pattern LEDE_BLACKLIST = Pattern.compile(
            "^(in this (post|article|blog)|at [a-z]+ we |today (i want|we will|we are)" +
            "|welcome to |this is (a|the) post|we('re| are) (excited|happy|thrilled|pleased)" +
            "|hello (world|readers)|subscribe to |follow us )",
            Pattern.CASE_INSENSITIVE);

    // Marketing filler that signals low-information sentences.
    private static final Set<String> MARKETING_WORDS = Set.of(
            "amazing", "powerful", "easy to use", "seamlessly", "revolutionary",
            "game-changer", "state-of-the-art", "cutting-edge", "best-in-class");

    // Common English stop-words excluded from technical-word scoring.
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "that", "this", "with", "from", "have",
            "been", "will", "are", "was", "were", "not", "but", "also",
            "can", "its", "our", "their", "about", "which", "when", "where",
            "what", "how", "some", "more", "would", "could", "should",
            "then", "than", "there", "these", "those", "into", "over",
            "such", "each", "after", "before", "between", "through");

    public static void main(String[] args) throws IOException {
        // Build url → docId from blog_doc_meta.txt
        Map<String, Integer> urlToDocId = buildUrlToDocIdMap();
        if (urlToDocId.isEmpty()) {
            System.err.println("ERROR: blog_doc_meta.txt not found or empty — run BlogIndexer first");
            System.exit(1);
        }

        Gson gson = new Gson();
        Map<Integer, String> tldrs = new HashMap<>();
        int nullCount = 0;

        // Walk BLOGS/**/*.json
        Path blogsDir = Path.of("BLOGS");
        if (!Files.exists(blogsDir)) {
            System.err.println("ERROR: BLOGS/ directory not found");
            System.exit(1);
        }

        List<Path> jsonFiles;
        try (Stream<Path> walk = Files.walk(blogsDir)) {
            jsonFiles = walk
                    .filter(p -> p.toString().endsWith(".json"))
                    .collect(Collectors.toList());
        }

        for (Path jsonPath : jsonFiles) {
            try (Reader reader = new FileReader(jsonPath.toFile())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> doc = gson.fromJson(reader, Map.class);
                if (doc == null) continue;

                Object urlObj = doc.get("url");
                if (urlObj == null) continue;
                String url = Parser.normalizeUrl(urlObj.toString());
                Integer docId = urlToDocId.get(url);
                if (docId == null) continue;

                Object textObj = doc.get("text");
                String body = (textObj != null) ? textObj.toString().trim() : "";
                String tldr = extractTldr(body);
                if (tldr != null) {
                    tldrs.put(docId, tldr);
                } else {
                    nullCount++;
                }
            } catch (Exception e) {
                // Skip malformed JSON files; log first few for diagnosis.
                System.err.println("WARN: failed to parse " + jsonPath + ": " + e.getMessage());
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(Path.of("blog_tldr.txt"))) {
            for (Map.Entry<Integer, String> entry : tldrs.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue());
                writer.newLine();
            }
        }

        System.out.printf("Wrote blog_tldr.txt: %d docs, %d nulls%n", tldrs.size(), nullCount);
    }

    // Reads blog_doc_meta.txt (space-separated 7-field format from BlogIndexer)
    // and returns url → docId. Mirrors BlogIndexer.readBlogDocMetadata parsing.
    static Map<String, Integer> buildUrlToDocIdMap() {
        Map<String, Integer> map = new HashMap<>();
        Path path = Path.of("blog_doc_meta.txt");
        if (!Files.exists(path)) return map;
        try (Scanner scanner = new Scanner(path.toFile())) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length != 7) continue;
                int id = Integer.parseInt(parts[0]);
                String url = parts[1];
                map.put(url, id);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }

    /**
     * Lede-skip extractive summary.
     * Returns null if no suitable sentences are found or body is blank.
     * Throws IllegalStateException (build gate) if result starts with a LEDE_BLACKLIST pattern.
     */
    static String extractTldr(String body) {
        if (body == null || body.isBlank()) return null;

        String[] rawSentences = SENTENCE_SPLIT.split(body);
        List<String> candidates = new ArrayList<>();
        for (String s : rawSentences) {
            String sentence = s.replaceAll("\\s+", " ").trim();
            // Strip newlines that survived the split
            sentence = sentence.replace('\n', ' ').replace('\r', ' ');
            if (sentence.length() < 40) continue;
            if (LEDE_BLACKLIST.matcher(sentence).find()) continue;
            candidates.add(sentence);
        }

        if (candidates.isEmpty()) return null;

        // Score each sentence
        int[] scores = new int[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            String sentence = candidates.get(i);
            String lower = sentence.toLowerCase();

            // +2 per technical word (length >= 6, not a stop-word)
            for (String word : lower.split("[^a-z]+")) {
                if (word.length() >= 6 && !STOP_WORDS.contains(word)) {
                    scores[i] += 2;
                }
            }

            // +1 per number / percentage
            Matcher numMatcher = Pattern.compile("\\d").matcher(sentence);
            while (numMatcher.find()) {
                scores[i]++;
                numMatcher.region(numMatcher.end(), sentence.length()); // only count once per group
                break; // one +1 per sentence is sufficient; count digit-containing words instead
            }
            // recount: +1 for each word containing a digit (more granular than single match)
            for (String word : sentence.split("\\s+")) {
                if (word.matches(".*\\d.*")) scores[i]++;
            }

            // -1 per marketing phrase
            for (String phrase : MARKETING_WORDS) {
                if (lower.contains(phrase)) scores[i]--;
            }
        }

        // Pick top-2 by score, preserving original order
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) indices.add(i);
        List<Integer> sorted = new ArrayList<>(indices);
        sorted.sort((a, b) -> Integer.compare(scores[b], scores[a]));

        List<Integer> top2 = sorted.subList(0, Math.min(2, sorted.size()));
        // Restore original order for readability
        top2 = new ArrayList<>(top2);
        Collections.sort(top2);

        StringBuilder sb = new StringBuilder();
        for (int idx : top2) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(candidates.get(idx));
        }

        String result = sb.toString();

        // Cap at 280 chars
        if (result.length() > 280) {
            result = result.substring(0, 279) + '…';
        }

        // Remove any residual newlines from the final string
        result = result.replace('\n', ' ').replace('\r', ' ').trim();

        // HARD FAIL: result must not start with a lede-blacklist pattern
        if (LEDE_BLACKLIST.matcher(result).find()) {
            throw new IllegalStateException(
                    "HARD FAIL: TL;DR starts with blacklisted lede pattern: " + result);
        }

        return result.isBlank() ? null : result;
    }
}
