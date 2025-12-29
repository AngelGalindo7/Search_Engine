package com.example;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.io.IOException;
import com.google.gson.Gson;
import java.util.Map;
import java.net.URL;
import java.net.HttpURLConnection;

public class FilterDEV {
    public static void main(String[] args) {
        Path dir = Paths.get("DEV");

        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .forEach(path -> {
                        try {
                            if (!isValidURL(getURLString(path))) {
                                Files.delete(path);
                            }
                        } catch(IOException e) {
                            System.out.println(e.getMessage());
                        }

                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            System.out.println(e.getMessage());
                        }

                  });
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

    }

    public static String getURLString(Path path) throws IOException {
        Gson gson = new Gson();
        String jsonString = Files.readString(path);
        Map<String, String> jsonMap = gson.fromJson(jsonString, Map.class);

        return jsonMap.get("url");

    }

    public static boolean isValidURL(String URLString) throws IOException {
        URL url = new URL(URLString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
    }
}
