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

    static Map<String, List<Posting>> invertedIndex = new HashMap<>();
    static Map<Integer, BlogDocMeta> docMetadata = new HashMap<>();
    static Map<String, TokenMeta> tokenMetadata = new HashMap<>();
    static Set<String> seenUrls = new HashSet<>();

    public static void main(String[] args) {
        indexDirectory();
        System.out.println("FINISHED indexing " + docId + " documents");
        System.out.println("Size of invertedIndex " + invertedIndex.size());
        String probe = "engineering";
        List<Posting> probePostings = invertedIndex.get(probe);
        System.out.println("Size of \"" + probe + "\" postings " + (probePostings == null ? 0 : probePostings.size()));
        System.out.println("Size of docMetadata " + docMetadata.size());

        writeBlogIndex();
        writeBlogDocMetadata();
        writeBlogTokenMetadata();

        Map<Integer, BlogDocMeta> doctest = readBlogDocMetadata();
        System.out.println("SIZE :" + doctest.size());

        BlogDocMeta t = doctest.get(0);
        if (t != null) {
            System.out.println("doc id 0: " + t.url + " | len=" + t.length + " | title=" + t.title
                    + " | company=" + t.company + " | date=" + t.postDate + " | pr=" + t.pageRank);
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
        if (url == null || seenUrls.contains(url)) return;
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
            // Bigrams only in TITLE/HEADING — body bigrams (~1.3M unique types) OOM at 256m heap.
            // Title/heading bigrams still capture "write-ahead logging", "circuit breaker", etc.
            // with the highest tag multiplier (3× / 2×) so they contribute strong BM25 signal.
            if (tag == Tag.TITLE || tag == Tag.HEADING) {
                for (String bigram : Tokenizer.getNGrams(tokens, 2)) {
                    TokenResult tr = tokenMap.computeIfAbsent(bigram, k -> new TokenResult());
                    tr.tf += 1;
                    tr.tagMask |= tag.bit;
                }
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
    }
}
