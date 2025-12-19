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
import java.text.Normalizer;
import java.io.FileWriter;

import java.util.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import javax.management.RuntimeErrorException;

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
        createPartialIndex("alderis_ics_uci_edu");
    }

    public static List<String> tokenize(String text) {

        
        text = text.toLowerCase();
        //removes all non ascii characters
        text = text.replaceAll("[^a-z0-9\\s]"," ");
        String[ ] tokensArray = text.split("\\s+");
        

        //TODO: Add stemming

        //TODO: Add word positions

        //TODO: Store hyperlinks for page

        //TODO: Frequeny TF 

        //TODO: Look into 

        
        List<String> tokens = new ArrayList<>();
        for (String token : tokensArray) {
            if (token.length() > 2) {
                tokens.add(token);
            }
        }
        return tokens;

    }
    //Change to return tokens
    public static List<String> parseJsonAndHtml(String resourcePath) {
        Gson gson = new Gson();

        try (InputStream in = App.class
                .getClassLoader()
                .getResourceAsStream(resourcePath)){
                
            if (in == null) {
                throw new RuntimeException("Resource not found: " + resourcePath);
            }
            JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();

            String html = root.has("content") ? root.get("content").getAsString() : "";

            Document doc = Jsoup.parse(html);
            

            System.out.println("Title: " + doc.title());
            System.out.println("Text: " + doc.body().text());

            Element contentDiv = doc.getElementById("content");
        

            if (contentDiv == null) { 
                //replace terminal log with file logging
                System.out.println("No content div found");
                return List.of();
            }

            List<String> tokens = new ArrayList<>();
            Elements relevantTags = contentDiv.select("h1, h2, h3, p, li");

            for (Element elem : relevantTags) {
                
                    // Only process if this element doesn't have a parent that is also in our list
    if (elem.parents().stream().noneMatch(p -> p.is("h1, h2, h3, p, li"))) {
        tokens.addAll(tokenize(elem.text()));
    }
}
                
            
            System.out.println("Total tokens extracted: " + tokens.size());
            System.out.println("All tokens" + tokens);
            return tokens;

        

    }       

        catch (IOException e) {
            System.err.println("Failed to read JSON file: " + resourcePath);
            e.printStackTrace();
            return List.of();
        }

         
    }
    private static void writeIndexToFile(Map<String, Set<Integer>> index) {
        try (FileWriter writer = new FileWriter("inverted_index.txt")) {
            for (String token : index.keySet()) {
                    writer.write(token + " : " + index.get(token) + "\n");
                
                
            }   
        
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void createPartialIndex(String Folder) {

        int docId = 0;
        Map<String, Set<Integer>> invertedIndex = new HashMap<>();

        try {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL resource = cl.getResource(Folder);

        Path folderPath = Paths.get(resource.toURI());

        try(Stream<Path> files = Files.list(folderPath)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                docId ++;

                String resourceName = Folder + "/" + path.getFileName().toString();
                System.out.println("Indexing docId=" + docId + " =" + path.getFileName());
                List<String> tokens = parseJsonAndHtml(resourceName);
                Set<String> uniqueTokens = new HashSet<>(tokens);

                for (String token: uniqueTokens) {
                invertedIndex.computeIfAbsent(token, k -> new HashSet<>()).add(docId);
            }


            }
        }
    } catch(Exception e) {
        throw new RuntimeException(e);
    }
    writeIndexToFile(invertedIndex);
            

    }


}
// look into json vs txt