package com.example;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RobotsCheck {

    private static final Map<String, List<String>> CACHE = new HashMap<>();
    private static final int TIMEOUT_MS = 10_000;

    public static boolean isAllowed(String url, String userAgent) {
        try {
            if (!SecurityGuards.isUrlSafe(url)) return false;
            URI uri = URI.create(url);
            String host = uri.getScheme() + "://" + uri.getHost();
            List<String> disallows = CACHE.computeIfAbsent(host, RobotsCheck::fetchAndParse);

            String path = uri.getPath();
            if (path == null || path.isEmpty()) path = "/";
            for (String rule : disallows) {
                if (rule.isEmpty()) continue;
                if (matches(path, rule)) return false;
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static List<String> fetchAndParse(String host) {
        List<String> rules = new ArrayList<>();
        try {
            String robotsUrl = host + "/robots.txt";
            if (!SecurityGuards.isUrlSafe(robotsUrl)) return rules;
            HttpURLConnection conn = (HttpURLConnection) new URL(robotsUrl).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "SearchEngineProject/1.0");
            int code = conn.getResponseCode();
            if (code != 200) return rules;

            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                boolean inRelevantBlock = false;
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    int hash = line.indexOf('#');
                    if (hash >= 0) line = line.substring(0, hash).trim();
                    if (line.isEmpty()) {
                        inRelevantBlock = false;
                        continue;
                    }
                    String lower = line.toLowerCase();
                    if (lower.startsWith("user-agent:")) {
                        String agent = line.substring(11).trim();
                        inRelevantBlock = agent.equals("*"); // simple: only honor * block
                    } else if (inRelevantBlock && lower.startsWith("disallow:")) {
                        String rule = line.substring(9).trim();
                        rules.add(rule);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return rules;
    }

    private static boolean matches(String path, String rule) {
        // Basic glob: supports leading prefix match + '*' wildcard + '$' end-anchor
        if (!rule.contains("*") && !rule.endsWith("$")) {
            return path.startsWith(rule);
        }
        String regex = rule
                .replace(".", "\\.")
                .replace("*", ".*");
        if (regex.endsWith("\\$")) regex = regex.substring(0, regex.length() - 2) + "$";
        return path.matches("^" + regex + ".*");
    }
}
