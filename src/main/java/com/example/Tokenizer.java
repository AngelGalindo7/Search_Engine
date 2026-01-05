package com.example;

import java.util.*;

import opennlp.tools.parser.Parse;
import opennlp.tools.stemmer.PorterStemmer;

public class Tokenizer {
    public static void main(String[] args) {
        List<String> tokens = getNGrams(tokenize("wics ics hello test grace hopper"), 2);
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
    
    public static List<String> getNGrams(List<String> tokens, int n) {
        List<String> resultTokens = new ArrayList<>();

        if (tokens.size() < n) return resultTokens;

        for (int i = 0; i <= tokens.size() - n; i++) {
            StringBuilder nGram = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (j > 0) nGram.append(" ");
                nGram.append(tokens.get(i + j));
            }
            resultTokens.add(nGram.toString());
        }

        return resultTokens;
    }
}

