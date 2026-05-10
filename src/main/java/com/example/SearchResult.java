package com.example;

public final class SearchResult {
    public final int docId;
    public final double score;
    public final String title;
    public final String company;
    public final String url;
    public final String tldr;              // null when TL;DR cache not loaded
    // null when explain=false; populated for debug mode
    public final Double bm25Score;
    public final Double pageRankMultiplier;

    public SearchResult(int docId, double score, String title, String company, String url) {
        this(docId, score, title, company, url, null, null, null);
    }

    public SearchResult(int docId, double score, String title, String company, String url, String tldr) {
        this(docId, score, title, company, url, tldr, null, null);
    }

    public SearchResult(int docId, double score, String title, String company, String url,
                        String tldr, Double bm25Score, Double pageRankMultiplier) {
        this.docId = docId;
        this.score = score;
        this.title = title;
        this.company = company;
        this.url = url;
        this.tldr = tldr;
        this.bm25Score = bm25Score;
        this.pageRankMultiplier = pageRankMultiplier;
    }
}
