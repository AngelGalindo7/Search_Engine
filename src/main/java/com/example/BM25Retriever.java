package com.example;

import java.util.List;

public class BM25Retriever implements Retriever {
    @Override
    public List<SearchResult> search(String query, int topN) {
        return BlogSearch.bm25Search(query, topN, false);
    }
}
