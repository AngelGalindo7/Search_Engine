package com.example;

public final class SearchResult {
    public final int docId;
    public final double score;
    public final String title;
    public final String company;
    public final String url;

    public SearchResult(int docId, double score, String title, String company, String url) {
        this.docId = docId;
        this.score = score;
        this.title = title;
        this.company = company;
        this.url = url;
    }
}
