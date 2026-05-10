package com.example;

import java.util.List;

public interface Retriever {
    List<SearchResult> search(String query, int topN);
}
