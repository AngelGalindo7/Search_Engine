package com.example;

public class BlogDocMeta {
    public String url;
    public String title;
    public int length;
    public double pageRank;
    public String company;
    public String postDate;

    public BlogDocMeta(String url, int length, String title, double pageRank, String company, String postDate) {
        this.url = url;
        this.length = length;
        this.title = title;
        this.pageRank = pageRank;
        this.company = company == null ? "" : company;
        this.postDate = postDate == null ? "" : postDate;
    }
}
