package com.example;

import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public class Search {

    public static Map<String, TokenMeta> tokenMetadata;
    public static Map<Integer, DocMeta> docMetadata;
    public static final int TOTAL_DOCS = 55_385; 

    public static void main(String[] args) {
        loadDependencies();
        System.out.println("Loaded token metadata with " + tokenMetadata.size() + " tokens");
        System.out.println("Loaded doc metadata with " + docMetadata.size() + " documents");
        
        List<Posting> test = getTokenPostings("wic");
        System.out.println("Size of \"wic\" postings from retrieval " + test.size());

        String query = "grace hopper";
        search(query);

    }

    
    public static void loadDependencies() {
        tokenMetadata = Indexer.readTokenMetadata();
        docMetadata = Indexer.readDocMetadata();
    }

    public static void search(String query) {
        List<String> queryTokens = Tokenizer.tokenize(query);

        // mini inverted index of the query tokens
        HashMap<String, List<Posting>> queryIndex = new HashMap<>();
        for (String token : queryTokens) {
            List<Posting> postings = getTokenPostings(token);
            queryIndex.put(token, postings);
        }

        // now the search starts here
        HashMap<Integer, Double> docRank = new HashMap<>();
        for (Map.Entry<String, List<Posting>> entry : queryIndex.entrySet()) {
            String token = entry.getKey();
            int df = tokenMetadata.get(token).df;

            List<Posting> postings = entry.getValue();
            for (Posting p : postings) { 
                double tf = (double)p.tf / docMetadata.get(p.docId).length; // normalize tf
                double tfIdf = p.tf * (Math.log(TOTAL_DOCS / df) + 1);
                double score = docRank.getOrDefault(token, 0.0) + tfIdf;
                docRank.put(p.docId, score);
            }
        }

        List<Integer> result = new ArrayList<>(docRank.keySet());
        result.sort(Comparator.comparing(docRank::get).reversed());

        for (int i = 0; i < 10; i++) {
            int docId = result.get(i);
            System.out.println("DOC ID: " + docId + ": " + docMetadata.get(docId).url);
        }
    }

    public static List<Posting> getTokenPostings(String token) {
        TokenMeta tm = tokenMetadata.get(token);
        int df = tm.df;
        long offset = tm.offset;
        int length = tm.length; 

        List<Posting> postings = new ArrayList<>(df);

        try (FileChannel channel = FileChannel.open(Path.of("index.bin"), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(length);
            channel.read(buf, offset);
            buf.flip();

            for (int i = 0; i < df; i++) {
                int docId = buf.getInt();
                int tf = buf.getInt();
                int tagMask = buf.getInt();

                postings.add(new Posting(docId, tf, tagMask));
            }
        } catch (IOException e){
            e.printStackTrace();
        }

        return postings;
    }
}
