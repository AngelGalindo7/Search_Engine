
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

public class Tokenize {
 public static class TokenData {
        public int frequency = 0;
        public List<Integer> positions = new ArrayList<>();
    }

    public static class ParseResult {
    public Map<String, TokenData> tokens;
    public String url;

    public ParseResult(Map<String, TokenData> tokens, String url) {
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
}

