package com.example;

import java.util.*;

import opennlp.tools.parser.Parse;
import opennlp.tools.stemmer.PorterStemmer;

public class Tokenizer {
    public static void main(String[] args) {
        List<String> tokens = tokenize("wics ics");
        for (String token : tokens) {
            System.out.println(token);
        }
    }
    public static List<String> tokenize(String text) {
        text = text.toLowerCase()
                   .replaceAll("\\.", "")
                   .replaceAll("[^a-z0-9\\s]"," ");

        String[] tokenArray = text.split("\\s+");
        PorterStemmer stemmer = new PorterStemmer();

        List<String> resultTokens = new ArrayList<>();

        for (String token : tokenArray) {
            if (token.length() < 50) {
                String stemmed = stemmer.stem(token);
                resultTokens.add(stemmed);
            }
        }

        return resultTokens;
    }
}

