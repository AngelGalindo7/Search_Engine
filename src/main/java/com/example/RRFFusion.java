package com.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Reciprocal Rank Fusion: score(d) = 1/(k + rank_r1) + 1/(k + rank_r2).
// Docs appearing in only one list get 0 for the missing term.
// k=60 is the standard value that softens the dominance of rank-1.
public class RRFFusion implements Retriever {

    private final Retriever r1;
    private final Retriever r2;
    private final int k;

    public RRFFusion(Retriever r1, Retriever r2, int k) {
        this.r1 = r1;
        this.r2 = r2;
        this.k  = k;
    }

    @Override
    public List<SearchResult> search(String query, int topN) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        // Over-fetch to ensure both lists have enough overlap candidates.
        int candidates = topN * 3;

        List<SearchResult> list1 = r1.search(query, candidates);
        List<SearchResult> list2 = r2.search(query, candidates);

        // docId → accumulated RRF score
        Map<Integer, Double> rrfScores = new HashMap<>();
        // keep one SearchResult per docId for building the output (title/company/url)
        Map<Integer, SearchResult> docInfo  = new HashMap<>();

        for (int rank = 0; rank < list1.size(); rank++) {
            SearchResult r = list1.get(rank);
            double contrib = 1.0 / (k + rank + 1); // rank is 0-based so +1 for 1-based formula
            rrfScores.merge(r.docId, contrib, Double::sum);
            docInfo.putIfAbsent(r.docId, r);
        }
        for (int rank = 0; rank < list2.size(); rank++) {
            SearchResult r = list2.get(rank);
            double contrib = 1.0 / (k + rank + 1);
            rrfScores.merge(r.docId, contrib, Double::sum);
            docInfo.putIfAbsent(r.docId, r);
        }

        List<Integer> ranked = new ArrayList<>(rrfScores.keySet());
        ranked.sort((a, b) -> Double.compare(rrfScores.get(b), rrfScores.get(a)));

        int top = Math.min(topN, ranked.size());
        List<SearchResult> out = new ArrayList<>(top);
        for (int i = 0; i < top; i++) {
            int docId = ranked.get(i);
            SearchResult base = docInfo.get(docId);
            out.add(new SearchResult(docId, rrfScores.get(docId), base.title, base.company, base.url));
        }
        return out;
    }
}
