package com.example;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;

import java.io.IOException;

import java.util.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;


enum Tag {
    TITLE(1),
    HEADING(2),
    EMPHASIS(4),
    ANCHOR(8),
    BODY(16);

    final int bit;

    Tag(int bit) {
        this.bit = bit;
    }
}

class Node {
    String text;
    Tag tag;

    Node(String text, Tag tag) {
        this.text = text;
        this.tag = tag;
    }

    public String toString() {
        return tag + "(" + text + ")";
    }
}



public class Parser {
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

}

