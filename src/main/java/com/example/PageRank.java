package com.example;

import java.io.*;
import java.util.*;

import com.google.gson.Gson;
public class PageRank {

    private static final double DAMPING = 0.85;
    private static final double EPSILON = 1e-6;
    private static final int MAX_ITER = 100;

    private static Map<Integer, Set<Integer>> incoming = new HashMap<>();
    private static Map<Integer, Set<Integer>> outgoing = new HashMap<>();
    private static Map<Integer, Double> pageRank = new HashMap<>();
    
    private static Map<String, Integer> urlToId = new HashMap<>();

    public static Map<Integer, DocMeta> docMetadata = Indexer.readDocMetadata();


    public static void main(String[] args) {
        setupUrlMapping();
        createGraph();
        initPageRank();
        computePageRank();
        printPageRank();
        //verifySum();
        updateDocMetadata();

    }
    
    
    public static void setupUrlMapping() {

        
        for (Map.Entry<Integer, DocMeta> entry : docMetadata.entrySet()) {
            urlToId.put(entry.getValue().url, entry.getKey());
        }
    }

    public static void initPageRank() {
        int N = urlToId.size();
        if (N == 0) return;
        double initialValue = 1.0 / N;
        for (Integer id : urlToId.values()) {
            pageRank.put(id, initialValue);
        }
    }
    public static void createGraph() {
        Gson gson = new Gson();

        List<String> paths = Parser.parseDirectory("DEV");

        for (String filePath : paths) {
            try (Reader reader = new FileReader(filePath)) {
                Map<String, String> jsonMap = gson.fromJson(reader, Map.class);
                String srcUrl = jsonMap.get("url");
                Integer srcId = urlToId.get(srcUrl);

                if (srcId == null) continue;

                incoming.putIfAbsent(srcId, new HashSet<>());
                outgoing.putIfAbsent(srcId, new HashSet<>());

                List<String> links = Parser.retrieveLinks(jsonMap.get("content"));

                for (String dstUrl : links) {
                    Integer dstId = urlToId.get(dstUrl);

                    // We only care about links to pages that exist in our indexed corpus
                    if (dstId != null) {
                        outgoing.get(srcId).add(dstId);
                        incoming.putIfAbsent(dstId, new HashSet<>());
                        incoming.get(dstId).add(srcId);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void computePageRank() {
    int N = urlToId.size();
    if (N == 0) return;

    // 1. Initialize ranks: 1/N
    double init = 1.0 / N;
    for (Integer id : urlToId.values()) {
        pageRank.put(id, init);
    }

    for (int iter = 0; iter < MAX_ITER; iter++) {
        Map<Integer, Double> nextRank = new HashMap<>();
        double diff = 0.0;
        
        // 2. Calculate total rank from sink nodes (nodes with no outgoing links)
        double sinkRankStack = 0.0;
        for (Integer id : urlToId.values()) {
            if (outgoing.get(id) == null || outgoing.get(id).isEmpty()) {
                sinkRankStack += pageRank.get(id);
            }
        }

        // 3. Main PageRank Calculation
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

            // Modified formula: (Random Jump) + (Redistributed Sink Rank) + (Weighted In-link Rank)
            double rank = ((1.0 - DAMPING) / N) + 
                          (DAMPING * (sinkRankStack / N)) + 
                          (DAMPING * sum);
            
            nextRank.put(id, rank);
            diff += Math.abs(rank - pageRank.get(id));
        }

        pageRank = nextRank;

        if (diff < EPSILON) {
            System.out.println("Converged after " + (iter + 1) + " iterations");
            break;
        }
    }
}

public static void printPageRank() {
        List<Map.Entry<Integer, Double>> list = new ArrayList<>(pageRank.entrySet());
        list.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        System.out.println("Top 10 PageRank Scores:");
        int count = 0;
        for (Map.Entry<Integer, Double> entry : list) {
            if (count++ >= 10) break;
            System.out.printf("DocID: %d | Score: %.8f\n", entry.getKey(), entry.getValue());
        }
    }

    public static void updateDocMetadata() {
    for (Map.Entry<Integer, DocMeta> entry : docMetadata.entrySet()) {
        int docId = entry.getKey();
        DocMeta meta = entry.getValue();

        Double score = pageRank.getOrDefault(docId, 0.0);
        
        meta.pageRank = score;
    }
    
    saveMetadataToFile();
}

public static void saveMetadataToFile() {
    try (FileWriter writer = new FileWriter("doc_meta.txt")) {
        for (Map.Entry<Integer, DocMeta> entry : docMetadata.entrySet()) {
            int docId = entry.getKey();
            DocMeta meta = entry.getValue();
            
            // Format: ID URL LENGTH PAGERANK
            writer.write(docId + " " + meta.url + " " + meta.length + " " + meta.title.replace(" ", "^") + " "  + meta.pageRank + "\n");
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
}


//     public static void verifySum() {
//     double sum = 0.0;
//     for (double score : pageRank.values()) {
//         sum += score;
//     }
//     System.out.println("------------------------------------");
//     System.out.printf("Verification - Total PageRank Sum: %.10f\n", sum);
    
//     if (Math.abs(1.0 - sum) < 1e-5) {
//         System.out.println("Result: SUCCESS (Total rank is conserved)");
//     } else {
//         System.out.println("Result: WARNING (Rank leakage detected! Check sink node handling)");
//     }
//     System.out.println("------------------------------------");
// }
 }