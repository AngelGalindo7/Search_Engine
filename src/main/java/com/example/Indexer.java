package com.example;

import com.google.gson.Gson;

import java.io.FileWriter;

import java.io.IOException;
import java.io.FileReader;
import java.io.Reader;

import java.util.*;

import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import java.io.File;


class TokenResult {
    int tf;
    int tagMask;
}



class Posting {
    int docId;
    int tf;
    int tagMask;
    
    Posting(int docId, int tf, int tagMask) {
        this.docId = docId;
        this.tf = tf;
        this.tagMask = tagMask;
    }
}

class TokenMeta {
    int df;
    long offset;
    int length;

    TokenMeta(int df, long offset, int length) {
        this.df = df;
        this.offset = offset;
        this.length = length;
    }
}

class DocMeta {
    String url;
    String title;
    int length;
    double pageRank;

    DocMeta(String url, int length, String title) {
        this.url = url;
        this.length = length;
        this.title = title;
        this.pageRank = 0.0;
    }
}


class PostingTfIdf {
    int docId;
    double tfIdf;

    public PostingTfIdf(int docId, double tfIdf) {
        this.docId = docId;
        this.tfIdf = tfIdf;
    }
}

public class Indexer {

    static int docId = 0;


    static Map<String, List<Posting>> invertedIndex = new HashMap<>();
    static Map<Integer, DocMeta> docMetadata = new HashMap<>();
    static Map<String, TokenMeta> tokenMetadata = new HashMap<>();
    static Set<String> seenUrls = new HashSet<>();
    
    public static void main(String[] args) {
        indexDirectory();
        System.out.println("FINISHED indexing " + docId + " documents");
        System.out.println("Size of invertedIndex " + invertedIndex.size());
        System.out.println("Size of \"wic\" postings " + invertedIndex.get("wic").size());
        System.out.println("Size of docMetadata " + docMetadata.size());

        writeIndex();
        writeDocMetadata();
        writeTokenMetadata();


        Map<Integer, DocMeta> doctest = readDocMetadata();

        System.out.println("SIZE :" + doctest.size());

        DocMeta t = doctest.get(0);

        System.out.println("doc id 0: " + t.url + " " + t.length + " " + t.title);
    }



    public static void writeIndex() {
        List<String> sortedInvertedTokens = new ArrayList<>(invertedIndex.keySet());
        Collections.sort(sortedInvertedTokens);

        try (FileChannel channel = FileChannel.open(Path.of("index.bin"), StandardOpenOption.CREATE,
                                                                          StandardOpenOption.WRITE,
                                                                          StandardOpenOption.TRUNCATE_EXISTING)) {

            for (String term : sortedInvertedTokens) {
                String line = term;

                List<Posting> postings = invertedIndex.get(term);


                int df = postings.size();
                long offset = channel.position();
                int length = postings.size() * Integer.BYTES * 3; // 3 since posting has 3 ints of information

                ByteBuffer buf = ByteBuffer.allocate(length);

                for (Posting p : postings) {
                    buf.putInt(p.docId);
                    buf.putInt(p.tf);
                    buf.putInt(p.tagMask);
                }

                buf.flip(); // make it ready for channel write

                channel.write(buf);
                tokenMetadata.put(term, new TokenMeta(df, offset, length));
            }
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    public static Map<Integer, DocMeta> readDocMetadata() {
        Map<Integer, DocMeta> docMetadata = new HashMap<>();

        try (Scanner scanner = new Scanner(new File("doc_meta.txt"))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" ");
                if (parts.length != 4) continue;

                int docId = Integer.parseInt(parts[0]);
                String url = parts[1];
                int length = Integer.parseInt(parts[2]);
                String title = parts[3].replace("^", " ");

                docMetadata.put(docId, new DocMeta(url, length, title)); 
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return docMetadata;
    }

    public static void writeDocMetadata() {
        try (FileWriter writer = new FileWriter("doc_meta.txt")) {
            for (Map.Entry<Integer, DocMeta> entry : docMetadata.entrySet()) {
                int docId = entry.getKey();
                DocMeta meta = entry.getValue();
                writer.write(docId + " " + meta.url + " " + meta.length + " " + meta.title.replace(" ", "^") + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static Map<String, TokenMeta> readTokenMetadata() {
        Map<String, TokenMeta> tokenMetadata = new HashMap<>();

        try (Scanner scanner = new Scanner(new File("token_meta.txt"))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(" ");
                if (parts.length != 4) continue;

                String token = parts[0].replace("^", " ");
                int df = Integer.parseInt(parts[1]);
                long offset = Long.parseLong(parts[2]);
                int length = Integer.parseInt(parts[3]);

                tokenMetadata.put(token, new TokenMeta(df, offset, length)); 
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return tokenMetadata;
    }
    
    public static void writeTokenMetadata() {
        try (FileWriter writer = new FileWriter("token_meta.txt")) {
            for (Map.Entry<String, TokenMeta> entry : tokenMetadata.entrySet()) {
                String token = entry.getKey().replace(" ", "^");
                TokenMeta meta = entry.getValue();
                writer.write(token + " " + meta.df + " " + meta.offset + " " + meta.length + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static void indexDirectory() {
        List<String> filePaths = Parser.parseDirectory("DEV");
        for (String filePath : filePaths) {
            try (Reader reader = new FileReader(filePath)) {
                Gson gson = new Gson();
                Map<String, String> jsonMap = gson.fromJson(reader, Map.class);
                String doc_url = Parser.normalizeUrl(jsonMap.get("url"));
                List<Node> doc_nodes = Parser.parseContent(jsonMap.get("content"));

                indexDocument(doc_url, doc_nodes);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public static void indexDocument(String url, List<Node> doc_nodes) {
        
        if (seenUrls.contains(url)) {
        return; 
    }
    
        seenUrls.add(url);
        
        int currentDocId = docId++;

        //System.out.println(currentDocId + " " + url);

        // tokenMap calculates the tf and tag mask for each token in the document
        // which can be used to create the inverted index
        Map<String, TokenResult> tokenMap = new HashMap<>();
        String title = "N/A"; // kludge, but dont want to make repeated json parse again

        // populate tokenMap
        for (Node node : doc_nodes) {
            String text = node.text;
            Tag tag = node.tag;

            if (tag == Tag.TITLE) title = text;

            List<String> tokens = Tokenizer.tokenize(text);
            // tokens.addAll(Tokenizer.getNGrams(tokens, 2)); // bi-grams

            for (String token : tokens) {
                if (token.isEmpty()) continue;
                TokenResult tr = tokenMap.computeIfAbsent(token, k -> new TokenResult());
                tr.tf += 1;
                tr.tagMask |= tag.bit;
            }
        }

        // populate invertedIndex
        for (Map.Entry<String, TokenResult> token : tokenMap.entrySet()) {
            String term = token.getKey();
            TokenResult tr = token.getValue();

            invertedIndex.computeIfAbsent(term, k -> new ArrayList())
                         .add(new Posting(currentDocId, tr.tf, tr.tagMask));
        }

        // calculate 
        // populate docMetaData
        int docLength = tokenMap.values().stream().mapToInt(tr -> tr.tf).sum();
        docMetadata.put(currentDocId, new DocMeta(url, docLength, title));
    }


    // FOR SEARCH
    //
    //
    //
    //
    //

    public  static Map<String, List<PostingTfIdf>> computeTfIdf(Map<String,List<Posting>> invertedIndex, Map<Integer, DocMeta> docMetadata) {
        Map<String, List<PostingTfIdf>> tfIdfMap = new HashMap<>();
        int totalDocs = docMetadata.size();

        for (Map.Entry<String, List<Posting>> entry: invertedIndex.entrySet()){

            String term = entry.getKey();
            List<Posting> postings = entry.getValue();
            
            int df = postings.size();
            double idf = Math.log((double)(totalDocs + 1) / (df + 1)) + 1; // smoothed IDF

            List<PostingTfIdf> tfIdfList = new ArrayList<>(); 
            for(Posting p: postings){
                int docLength = docMetadata.get(p.docId).length;

                double tf = (double) p.tf / docLength;
                double tfIdf = idf * tf;
                tfIdfList.add(new PostingTfIdf(p.docId,tfIdf));

            }

        tfIdfMap.put(term,tfIdfList);
        }
    
    return tfIdfMap;
    }

    public static void writeTfIdfMap(Map<String, List<PostingTfIdf>> tfIdfMap) {
    List<String> sortedTerms = new ArrayList<>(tfIdfMap.keySet());
    Collections.sort(sortedTerms); // optional: alphabetical order

    try (FileWriter writer = new FileWriter("term_tf_idf.txt")) {

        for (String term : sortedTerms) {
            writer.write(term + " ->");

            List<PostingTfIdf> postings = tfIdfMap.get(term);
            for (PostingTfIdf p : postings) {
                // write as docId:tfIdf
                writer.write(" " + p.docId + ":" + String.format("%.6f", p.tfIdf));
            }

            writer.write("\n");
        }

        System.out.println("TF-IDF map written to " + "term_tf_idf.txt");

    } catch (IOException e) {
        e.printStackTrace();
    }
}
}

