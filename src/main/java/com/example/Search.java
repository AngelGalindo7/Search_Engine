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
            double idf = Math.log(TOTAL_DOCS / tokenMetadata.get(token).df) + 1;

            List<Posting> postings = entry.getValue();
            for (Posting p : postings) { 
                double tf = (double) p.tf / docMetadata.get(p.docId).length;
                double tagMult = getTagMultiplier(p.tagMask);

                // tf-idf + tag boost
                double score = (p.tf * idf) * tagMult;
                                                   ;
                docRank.put(p.docId, docRank.getOrDefault(token, 0.0) + score);
            }
        }

        List<Integer> result = new ArrayList<>(docRank.keySet());
        result.sort(Comparator.comparing(docRank::get).reversed());

        for (int i = 0; i < 20; i++) {
            int docId = result.get(i);
            System.out.println("DOC ID: " + docId + ": " + docMetadata.get(docId).url);
        }
    }
    
    public static double getTagMultiplier(int tagMask) {
        // Some tags might have multiple bits on, so this returns the highest tag bit instead
        if ((tagMask & Tag.TITLE.bit) != 0) return 3.0;
        if ((tagMask & Tag.HEADING.bit) != 0) return 2.0;
        if ((tagMask & Tag.ANCHOR.bit) != 0) return 2.0;
        if ((tagMask & Tag.EMPHASIS.bit) != 0) return 1.5;
        if ((tagMask & Tag.BODY.bit) != 0) return 1.0;

        return 1.0;
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
