package com.example;

public class BlogSource {
    public final String name;
    public final String rssUrl;
    public final String htmlUrl;

    public BlogSource(String name, String rssUrl, String htmlUrl) {
        this.name = name;
        this.rssUrl = rssUrl;
        this.htmlUrl = htmlUrl;
    }

    @Override
    public String toString() {
        return name + " [rss=" + rssUrl + ", html=" + htmlUrl + "]";
    }
}
