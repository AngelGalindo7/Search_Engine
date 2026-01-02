package com.example;

import com.google.gson.Gson;

import java.io.FileWriter;

import java.io.IOException;
import java.io.FileReader;
import java.io.Reader;

import java.util.*;


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

class DocMeta {
    String url;
    int length;

    DocMeta(String url, int length) {
        this.url = url;
        this.length = length;
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

    public static void main(String[] args) {
        indexDirectory();
        System.out.println("FINISHED indexing " + docId + " documents");
        System.out.println("Size of invertedIndex " + invertedIndex.size());
        System.out.println("Size of \"wic\" postings " + invertedIndex.get("wic").size());
        System.out.println("Size of docMetadata " + docMetadata.size());

        writeIndex();
        writeDocMetadata();
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

    public static void writeIndex() {
        List<String> sortedInvertedTokens = new ArrayList<>(invertedIndex.keySet());
        Collections.sort(sortedInvertedTokens);
        
        // You can use this to write to file, etc...
        try (FileWriter writer = new FileWriter("inverted_index.txt")) {
            for (String term :sortedInvertedTokens) {
                writer.write(term + " -> ");

                List<Posting> postings = invertedIndex.get(term);

                for (Posting p : postings) {
                    writer.write(" " + p.docId + ":" + p.tf + ":" + p.tagMask);
                }

                writer.write("\n");


            }
        }
        catch (IOException e){
            e.printStackTrace();
        }

    }
    public static void writeDocMetadata() {
    try (FileWriter writer = new FileWriter("doc_metadata.txt")) {
        for (Map.Entry<Integer, DocMeta> entry : docMetadata.entrySet()) {
            int docId = entry.getKey();
            DocMeta meta = entry.getValue();
            writer.write(docId + "|" + meta.url + "|" + meta.length + "\n");
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
                String doc_url = jsonMap.get("url");
                List<Node> doc_nodes = Parser.parseContent(jsonMap.get("content"));

                indexDocument(doc_url, doc_nodes);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public static void indexDocument(String url, List<Node> doc_nodes) {
        int currentDocId = docId++;

        System.out.println(currentDocId + " " + url);

        // tokenMap calculates the tf and tag mask for each token in the document
        // which can be used to create the inverted index
        Map<String, TokenResult> tokenMap = new HashMap<>();

        // populate tokenMap
        for (Node node : doc_nodes) {
            String text = node.text;
            Tag tag = node.tag;

            List<String> tokens = Tokenizer.tokenize(text);
            for (String token : tokens) {
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
        docMetadata.put(currentDocId, new DocMeta(url, docLength));
    }

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
}

