package com.example;

import java.util.*;
import java.util.regex.Pattern;

import opennlp.tools.parser.Parse;
import opennlp.tools.stemmer.PorterStemmer;

public class Tokenizer {
    // Compiled once — String.replaceAll recompiles the pattern on every call,
    // which causes GC pressure proportional to the number of tokenize() invocations.
    private static final Pattern DOT        = Pattern.compile("\\.");
    private static final Pattern NON_ALNUM  = Pattern.compile("[^a-z0-9\\s]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // PorterStemmer is not thread-safe but indexing is single-threaded; one instance is fine.
    private static final PorterStemmer STEMMER = new PorterStemmer();

    public static void main(String[] args) {
        List<String> tokens = getNGrams(tokenize("wics ics hello test grace hopper"), 2);
        for (String token : tokens) {
            System.out.println(token);
        }
    }

    public static List<String> tokenize(String text) {
        text = NON_ALNUM.matcher(DOT.matcher(text.toLowerCase()).replaceAll("")).replaceAll(" ");

        // String[] tokenArray = text.split("\\s+");
        String[] tokenArray = WHITESPACE.split(text);

        // PorterStemmer stemmer = new PorterStemmer();
        List<String> resultTokens = new ArrayList<>();

        for (String token : tokenArray) {
            if (token.length() < 50) {
                String stemmed = STEMMER.stem(token);
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

