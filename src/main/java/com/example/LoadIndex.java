package com.example;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class LoadIndex {

    public static Map<String, List<Posting>> invertedIndex = new HashMap<>();
    public static Map<Integer, DocMeta> docMetadata = new HashMap<>();

    public static void main(String[] args) {
        loadInvertedIndex("inverted_index.txt");
        loadDocMetadata("doc_metadata.txt");

        System.out.println("Loaded inverted index with " + invertedIndex.size() + " terms");
        System.out.println("Loaded doc metadata with " + docMetadata.size() + " documents");
    }

    public static void loadInvertedIndex(String fileName) {
        invertedIndex.clear();

        try (Scanner scanner = new Scanner(new FileReader(fileName))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" -> ");
                if (parts.length != 2) continue;

                String term = parts[0];
                String postingsStr = parts[1];

                List<Posting> postings = new ArrayList<>();
                for (String pStr : postingsStr.trim().split("\\s+")) {
                    String[] pParts = pStr.split(":");
                    if (pParts.length != 3) continue;

                    int docId = Integer.parseInt(pParts[0]);
                    int tf = Integer.parseInt(pParts[1]);
                    int tagMask = Integer.parseInt(pParts[2]);

                    postings.add(new Posting(docId, tf, tagMask));
                }

                invertedIndex.put(term, postings);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void loadDocMetadata(String fileName) {
        docMetadata.clear();

        try (Scanner scanner = new Scanner(new FileReader(fileName))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" -> ");
                if (parts.length != 2) continue;

                int docId = Integer.parseInt(parts[0]);
                String url = parts[1];

                docMetadata.put(docId, new DocMeta(url, 0)); 
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
