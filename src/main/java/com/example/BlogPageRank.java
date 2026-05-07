package com.example;

import com.google.gson.Gson;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.*;

/**
 * Blog-corpus parallel of {@link PageRank}.
 *
 * Reads blog_doc_meta.txt for the doc set, walks BLOGS/ for HTML to extract outbound links via
 * {@link Parser#retrieveLinks(String)}, builds the inter-blog citation graph (edges only between
 * URLs that exist in the indexed corpus), and writes pageRank back to blog_doc_meta.txt.
 *
 * No file in the original DEV pipeline is touched.
 */
public class BlogPageRank {

    private static final double DAMPING = 0.85;
    private static final double EPSILON = 1e-6;
    private static final int MAX_ITER = 100;

    private static Map<Integer, Set<Integer>> incoming = new HashMap<>();
    private static Map<Integer, Set<Integer>> outgoing = new HashMap<>();
    private static Map<Integer, Double> pageRank = new HashMap<>();
    private static Map<String, Integer> urlToId = new HashMap<>();
    private static Map<Integer, BlogDocMeta> docMetadata;
    private static long intraDomainSkipped = 0;
    private static int iterationsRun = 0;
    private static boolean converged = false;

    public static void main(String[] args) {
        long startMs = System.currentTimeMillis();
        docMetadata = BlogIndexer.readBlogDocMetadata();
        if (docMetadata.isEmpty()) {
            System.err.println("blog_doc_meta.txt is empty or missing — run BlogIndexer first.");
            return;
        }

        setupUrlMapping();
        createGraph();
        printGraphStats();
        initPageRank();
        computePageRank();
        printPageRank();
        updateDocMetadata();
        saveMetadataToFile();

        long durationSec = (System.currentTimeMillis() - startMs) / 1000;
        writePageRankManifest(durationSec);
    }

    private static void writePageRankManifest(long durationSec) {
        try {
            int totalEdges = 0, sinks = 0;
            for (Integer id : urlToId.values()) {
                Set<Integer> out = outgoing.get(id);
                if (out == null || out.isEmpty()) sinks++;
                else totalEdges += out.size();
            }
            int N = urlToId.size();

            com.google.gson.JsonObject m = new com.google.gson.JsonObject();
            m.addProperty("timestamp", java.time.Instant.now().toString());
            m.addProperty("duration_sec", durationSec);
            m.addProperty("damping", DAMPING);
            m.addProperty("epsilon", EPSILON);
            m.addProperty("max_iter", MAX_ITER);
            m.addProperty("iterations_run", iterationsRun);
            m.addProperty("converged", converged);
            m.addProperty("nodes", N);
            m.addProperty("edges", totalEdges);
            m.addProperty("sinks", sinks);
            m.addProperty("intra_domain_edges_filtered", intraDomainSkipped);
            m.addProperty("mean_out_degree", N == 0 ? 0.0 : (double) totalEdges / N);

            java.nio.file.Files.createDirectories(java.nio.file.Path.of("eval"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("eval/pagerank_manifest.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(m));
            System.out.println("Written eval/pagerank_manifest.json");
        } catch (Exception e) {
            System.err.println("pagerank manifest write failed: " + e.getMessage());
        }
    }

    public static void setupUrlMapping() {
        for (Map.Entry<Integer, BlogDocMeta> entry : docMetadata.entrySet()) {
            urlToId.put(entry.getValue().url, entry.getKey());
        }
    }

    public static void initPageRank() {
        int N = urlToId.size();
        if (N == 0) return;
        double init = 1.0 / N;
        for (Integer id : urlToId.values()) {
            pageRank.put(id, init);
        }
    }

    public static void createGraph() {
        Gson gson = new Gson();
        List<String> paths = Parser.parseDirectory("BLOGS");
        if (paths == null) return;

        // long intraDomainSkipped = 0;   // promoted to class field for pagerank_manifest.json
        for (String filePath : paths) {
            try (Reader reader = new FileReader(filePath)) {
                Map<String, String> jsonMap = gson.fromJson(reader, Map.class);
                String srcUrl = Parser.normalizeUrl(jsonMap.get("url"));
                Integer srcId = urlToId.get(srcUrl);
                if (srcId == null) continue;
                String srcHost = registrableDomain(srcUrl);

                incoming.putIfAbsent(srcId, new HashSet<>());
                outgoing.putIfAbsent(srcId, new HashSet<>());

                List<String> links = Parser.retrieveLinks(jsonMap.get("content"));
                for (String dstUrl : links) {
                    Integer dstId = urlToId.get(dstUrl);
                    // if (dstId != null && !dstId.equals(srcId)) {
                    if (dstId == null || dstId.equals(srcId)) continue;
                    if (!srcHost.isEmpty() && srcHost.equals(registrableDomain(dstUrl))) {
                        intraDomainSkipped++;
                        continue;
                    }
                    outgoing.get(srcId).add(dstId);
                    incoming.putIfAbsent(dstId, new HashSet<>());
                    incoming.get(dstId).add(srcId);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        System.out.println("Intra-domain edges filtered: " + intraDomainSkipped);
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

    public static void printGraphStats() {
        int totalEdges = 0;
        int sinkCount = 0;
        for (Integer id : urlToId.values()) {
            Set<Integer> out = outgoing.get(id);
            if (out == null || out.isEmpty()) sinkCount++;
            else totalEdges += out.size();
        }
        int N = urlToId.size();
        double meanOut = N == 0 ? 0 : (double) totalEdges / N;
        System.out.printf("Graph: %d nodes | %d edges | %d sinks (%.1f%%) | mean out-degree %.2f%n",
                N, totalEdges, sinkCount, 100.0 * sinkCount / Math.max(1, N), meanOut);
    }

    public static void computePageRank() {
        int N = urlToId.size();
        if (N == 0) return;

        for (int iter = 0; iter < MAX_ITER; iter++) {
            Map<Integer, Double> nextRank = new HashMap<>();
            double diff = 0.0;

            double sinkRankStack = 0.0;
            for (Integer id : urlToId.values()) {
                if (outgoing.get(id) == null || outgoing.get(id).isEmpty()) {
                    sinkRankStack += pageRank.get(id);
                }
            }

            for (Integer id : urlToId.values()) {
                double sum = 0.0;
                Set<Integer> inLinks = incoming.get(id);
                if (inLinks != null) {
                    for (Integer inId : inLinks) {
                        int outDegree = outgoing.get(inId).size();
                        if (outDegree > 0) {
                            sum += pageRank.get(inId) / outDegree;
                        }
                    }
                }

                double rank = ((1.0 - DAMPING) / N)
                        + (DAMPING * (sinkRankStack / N))
                        + (DAMPING * sum);

                nextRank.put(id, rank);
                diff += Math.abs(rank - pageRank.get(id));
            }

            pageRank = nextRank;
            iterationsRun = iter + 1;
            if (diff < EPSILON) {
                converged = true;
                System.out.println("Converged after " + (iter + 1) + " iterations");
                break;
            }
        }
    }

    public static void printPageRank() {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(pageRank.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        System.out.println("Top 10 PageRank scores:");
        int count = 0;
        for (Map.Entry<Integer, Double> entry : list) {
            if (count++ >= 10) break;
            BlogDocMeta meta = docMetadata.get(entry.getKey());
            String label = meta == null ? "?" : (meta.company + " — " + meta.title);
            System.out.printf("DocID %5d | %.8f | %s%n", entry.getKey(), entry.getValue(), label);
        }
    }

    public static void updateDocMetadata() {
        for (Map.Entry<Integer, BlogDocMeta> entry : docMetadata.entrySet()) {
            int id = entry.getKey();
            BlogDocMeta meta = entry.getValue();
            meta.pageRank = pageRank.getOrDefault(id, 0.0);
        }
    }

    public static void saveMetadataToFile() {
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
}
