package com.example;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.text.Normalizer;
import java.io.FileWriter;

import java.util.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import javax.management.RuntimeErrorException;

import opennlp.tools.parser.Parse;
import opennlp.tools.stemmer.PorterStemmer;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args )
    {
        //alderis
        //read a json file
        //parseJsonAndHtml("192286a9954a2917a50ad6d5bb1efa61e2de5e94c7e9763d0d3c6e985677c6a5.json");
        createPartialIndex("SearchEngine/DEV/alderis_ics_uci_edu");
    }
    
    public static class TokenData {
        int frequency = 0;
        List<Integer> positions = new ArrayList<>();
    }

    public static class ParseResult {
    Map<String, TokenData> tokens;
    String url;

    ParseResult(Map<String, TokenData> tokens, String url) {
        this.tokens = tokens;
        this.url = url;
    }
}
    public static Map<String, TokenData> tokenize(String text, int positionOffset) {

        
        text = text.toLowerCase();
        //removes all non ascii characters
        text = text.replaceAll("[^a-z0-9\\s]"," ");
        String[ ] tokensArray = text.split("\\s+");
        PorterStemmer stemmer = new PorterStemmer();
        
        Map<String, TokenData> tokenMap = new HashMap<>();

        //TODO: Add word positions

        //TODO: Store hyperlinks for page

        //TODO: Frequeny TF 

        //TODO: Look into 

        
        for (String rawToken : tokensArray) {
            if (rawToken.length() > 2) {
                String stemmed = stemmer.stem(rawToken);
                TokenData data = tokenMap.computeIfAbsent(stemmed, k->new TokenData());
                data.frequency++;
                data.positions.add(positionOffset);
            }
            positionOffset++;
        }
        return tokenMap;

    }
    //Change to return tokens
    public static ParseResult parseJsonAndHtml(String filePath) {

        try (InputStream in = Files.newInputStream(Paths.get(filePath))) {

            JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
            String html = root.has("content") ? root.get("content").getAsString() : "";
            Document doc = Jsoup.parse(html);
            // Get the URL from the JSON
            String url = root.has("url") ? root.get("url").getAsString() : "unknown";

            // System.out.println("Title: " + doc.title());
            // System.out.println("Text: " + doc.body().text());

            Element contentDiv = doc.getElementById("content");
        

            if (contentDiv == null) { 
                //replace terminal log with file logging
                System.out.println("No content div found");
                return new ParseResult(Collections.emptyMap(), url);
            }

            // List<String> tokens = new ArrayList<>();
            Elements relevantTags = contentDiv.select("h1, h2, h3, p, li");
            
            int globalPositionOffset = 0;
            Map<String,TokenData> documentTokenMap = new HashMap<>();

            for (Element elem : relevantTags) {
                
                    // Only process if this element doesn't have a parent that is also in our list
    if (elem.parents().stream().noneMatch(p -> p.is("h1, h2, h3, p, li"))) {
            Map<String, TokenData> elementTokens = tokenize(elem.text(), globalPositionOffset);

            elementTokens.forEach((word,data) -> {
                TokenData existing = documentTokenMap.computeIfAbsent(word, k -> new TokenData());
                    existing.frequency += data.frequency;
                    existing.positions.addAll(data.positions);
            });
            globalPositionOffset += elem.text().split("\\s+").length;
    }
}
                
            
            System.out.println("Total tokens extracted: " + documentTokenMap.size());
            return new ParseResult(documentTokenMap, url);

        

    }       

        catch (IOException e) {
            System.err.println("Failed to read JSON file: " + filePath);
            e.printStackTrace();
            return null;
        }

         
    }
    private static void writeIndexToFile(Map<String, Map<Integer, TokenData>> index) {
        try (FileWriter writer = new FileWriter("inverted_index.txt")) {
            for (String term : index.keySet()) {
                    writer.write(term + " -> ");
                    Map<Integer, TokenData> postings = index.get(term);
                    List<String> docEntries = new ArrayList<>();

                    for (Map.Entry<Integer, TokenData> entry : postings.entrySet()) {
                        int docId = entry.getKey();
                        TokenData data = entry.getValue();

                        docEntries.add(String.format("%d:[%d:%s]",
                            docId,
                            data. frequency,
                            data.positions.toString()
                        ));
                    }

                    writer.write(String.join(", ", docEntries) + "\n");
                
                
            }   
        
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

private static void writeUrlsToFile(Map<Integer, String> urlMap) {
    try (FileWriter writer = new FileWriter("url_mapping.txt")) {
        for (Map.Entry<Integer, String> entry : urlMap.entrySet()) {
            writer.write(entry.getKey() + " -> " + entry.getValue() + "\n");
        }
    } catch (IOException e) {
        e.printStackTrace();
    }
}

    public static void createPartialIndex(String folderPathString) {

        int docId = 0;
        Map<String, Map<Integer, TokenData>> invertedIndex = new HashMap<>();
        
        Path folderPath = Paths.get(folderPathString);
        Map<Integer, String> urlMapping = new HashMap<>();

        try(Stream<Path> files = Files.list(folderPath)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                docId ++;

                String absolutePath = path.toAbsolutePath().toString();
                System.out.println("Indexing docId=" + docId + " =" + path.getFileName());
                ParseResult result = parseJsonAndHtml(absolutePath);

                if (result != null) {

                urlMapping.put(docId,result.url);
                for (Map.Entry<String, TokenData> entry: result.tokens.entrySet()) {
                    String term = entry.getKey();
                    TokenData stats = entry.getValue();

                    Map<Integer, TokenData> postings = invertedIndex.computeIfAbsent(term, k-> new HashMap<>());
                    postings.put(docId, stats);
            }
        }


            }
        }
     catch(Exception e) {
        throw new RuntimeException(e);
    }
    writeIndexToFile(invertedIndex);
    writeUrlsToFile(urlMapping);
            

    }


}
// look into json vs txt