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
    
    
    private static void writeIndexToFile(Map<String, Map<Integer, Tokenize.TokenData>> index) {
        try (FileWriter writer = new FileWriter("inverted_index.txt")) {
            for (String term : index.keySet()) {
                    writer.write(term + " -> ");
                    Map<Integer, Tokenize.TokenData> postings = index.get(term);
                    List<String> docEntries = new ArrayList<>();

                    for (Map.Entry<Integer, Tokenize.TokenData> entry : postings.entrySet()) {
                        int docId = entry.getKey();
                        Tokenize.TokenData data = entry.getValue();

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
        Map<String, Map<Integer, Tokenize.TokenData>> invertedIndex = new HashMap<>();
        
        Path folderPath = Paths.get(folderPathString);
        Map<Integer, String> urlMapping = new HashMap<>();

        try(Stream<Path> files = Files.list(folderPath)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                docId ++;

                String absolutePath = path.toAbsolutePath().toString();
                System.out.println("Indexing docId=" + docId + " =" + path.getFileName());
                Tokenize.ParseResult result = Tokenize.parseJsonAndHtml(absolutePath);

                if (result != null) {

                urlMapping.put(docId,result.url);
                for (Map.Entry<String, Tokenize.TokenData> entry: result.tokens.entrySet()) {
                    String term = entry.getKey();
                    Tokenize.TokenData stats = entry.getValue();

                    Map<Integer, Tokenize.TokenData> postings = invertedIndex.computeIfAbsent(term, k-> new HashMap<>());
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