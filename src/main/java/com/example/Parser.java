package com.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.text.Normalizer;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.Reader;

import java.util.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;


import javax.management.RuntimeErrorException;


enum Tag {
    TITLE,
    HEADING,
    EMPHASIS,
    ANCHOR,
    BODY,
}

class Node {
    String text;
    Tag tag;

    public Node(String text, Tag tag) {
        this.text = text;
        this.tag = tag;
    }
    
    public String toString() {
        return tag + "(" + text + ")";
    }
}

public class Parser {
    public static void main(String[] args) {
        List<String> paths = parseDirectory("DEV");
        parseDocument(paths.get(0));
    }

    public static List<String> parseDirectory(String dirPath) {
        List<String> filePaths = new ArrayList<>();
        Path dir = Paths.get(dirPath);

        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(path -> {
                        filePaths.add(path.toString()); 
                  });
        } catch (IOException e) {
            System.err.println("Failed to parse directory: " + dirPath);
            e.printStackTrace();
            return null;
        }

        return filePaths;
    }

    public static void parseDocument(String filePath) {
        try (Reader reader = new FileReader(filePath)) {
            Gson gson = new Gson();
            Map<String, String> jsonMap = gson.fromJson(reader, Map.class);
            System.out.println(jsonMap.get("url"));
            List<Node> elems = parseContent(jsonMap.get("content"));
            for (Node e : elems) {
                System.out.println(e);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static List<Node> parseContent(String html) {
        List<Node> nodes = new ArrayList<>();
        Document doc = Jsoup.parse(html);

        for (Element elem : doc.select("*")) {
            List<TextNode> tns = elem.textNodes();
            for (TextNode tn : tns) {
                String text = tn.text().trim();
                if (text.isEmpty()) {
                    continue;
                }

                Element parent = (Element)tn.parent();
                Tag tag = classifyTag(parent);
                nodes.add(new Node(text, tag));
            }
        }

        return nodes;
    }

    public static Tag classifyTag(Element element) {
        if (element == null) {
            return Tag.BODY;
        }

        switch (element.tagName().toLowerCase()) {
            case "title": return Tag.TITLE;
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": return Tag.HEADING;
            case "a": return Tag.ANCHOR;
            case "strong": case "b": case "em": case "i": return Tag.EMPHASIS;
            case "p": case "div": case "li": default: return Tag.BODY;
        }
    }

    // public static ParseResult parseDocument(String filePath) {
    //     try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
    //         JsonObject root = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
    //         String html = root.has("content") ? root.get("content").getAsString() : "";
            // Document doc = Jsoup.parse(html);
    //         // Get the URL from the JSON
    //         String url = root.has("url") ? root.get("url").getAsString() : "unknown";
    //
    //         // System.out.println("Title: " + doc.title());
    //         // System.out.println("Text: " + doc.body().text());
    //
    //         Element contentDiv = doc.getElementById("content");
    //
    //         if (contentDiv == null) { 
    //             //replace terminal log with file logging
    //             System.out.println("No content div found");
    //             return new ParseResult(Collections.emptyMap(), url);
    //         }
    //
    //         // List<String> tokens = new ArrayList<>();
    //         Elements relevantTags = contentDiv.select("h1, h2, h3, p, li");
    //         int globalPositionOffset = 0;
    //
    //         Map<String,TokenData> documentTokenMap = new HashMap<>();
    //
    //         for (Element elem : relevantTags) {
    //             // Only process if this element doesn't have a parent that is also in our list
    //             if (elem.parents().stream().noneMatch(p -> p.is("h1, h2, h3, p, li"))) {
    //                 Map<String, TokenData> elementTokens = tokenize(elem.text(), globalPositionOffset);
    //
    //                 elementTokens.forEach((word,data) -> {
    //                     TokenData existing = documentTokenMap.computeIfAbsent(word, k -> new TokenData());
    //                         existing.frequency += data.frequency;
    //                         existing.positions.addAll(data.positions);
    //                 });
    //                 globalPositionOffset += elem.text().split("\\s+").length;
    //             }
    //         }
    //         System.out.println("Total tokens extracted: " + documentTokenMap.size());
    //         return new ParseResult(documentTokenMap, url);
    //     } catch (IOException e) {
    //         System.err.println("Failed to read JSON file: " + filePath);
    //         e.printStackTrace();
    //         return null;
    //     }
    // }
}

