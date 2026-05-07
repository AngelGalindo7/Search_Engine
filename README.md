# Engineering Blog Search

Search engine over **13,594 posts** from **446 engineering blogs** (Stripe, Cloudflare, Netflix, Discord, Datadog, Uber, brooker.co.za, danluu.com, jvns.ca, etc). Built end-to-end in **Java 17** — custom binary inverted index, **BM25 + PageRank** ranking, and a **dense reranker** using **MiniLM** sentence embeddings. Search quality is measured with an **LLM-as-a-judge** eval harness powered by **OpenAI gpt-4o-mini**.

**Live:** [engineering-blog-search.onrender.com](https://engineering-blog-search.onrender.com/)

---

## Search quality

I built an **LLM-as-a-judge** eval harness on top of the backend: **44 hand-written queries** across 6 categories (plus 2 negative off-corpus probes), and **OpenAI `gpt-4o-mini`** scores every top-10 result 0–3 against a per-query rubric. Results are aggregated with the standard IR metrics:

- **NDCG@10** — *Normalized Discounted Cumulative Gain*. Measures how well-ordered the top 10 results are; 1.0 means the highest-relevance docs are at the top.
- **MRR** — *Mean Reciprocal Rank*. How high the first relevant result lands; 1.0 means it's always at position 1.
- **P@5** — *Precision at 5*. Fraction of the top 5 results that are relevant.

Judgments are cached in `eval/qrels.json` so a full re-eval after a tuning change costs cents, not dollars. The full chart, per-query results, and per-run archives live in `eval/` (rendered chart at `eval/metrics.png`, per-run snapshots in `eval/runs/<stamp>/`).

| Category | n | NDCG@10 |
|---|---:|---:|
| Ambiguous / hard (`auth`, `caching`, `logs`, `queues`) | 7 | **0.929** |
| Known-author probes | 3 | 0.838 |
| ML / data infra | 9 | 0.802 |
| Architecture concepts | 8 | 0.747 |
| Language / runtime internals | 9 | 0.717 |
| Systems & performance | 6 | 0.614 |
| **Overall (graded)** | **42** | **0.770** |
| Negative / off-corpus | 2 | 0 spurious hits |

Broad queries do well. Rare technical phrases like `coordinated omission` and `write amplification SSD` score poorly because those topics aren't really in the crawl — that's a recall problem, not a ranking one.

---

## Architecture

```
engineering_blogs.opml  (479 RSS feeds, 446 after Medium filter)
        │
        ▼
   Crawler ───► BLOGS/{company}/{hash}.json     RSS via Rome, HTML via SecurityGuards
        │                                       robots.txt, 1 req/s per domain
        ▼
   BlogIndexer ───► blog_index.bin              12-byte fixed-stride postings
                    blog_doc_meta.txt           (docId, tf, tagMask)
                    blog_token_meta.txt         dedup duplicate URLs in memory
        │
        ▼
   BlogPageRank ───► rewrites blog_doc_meta.txt with pageRank
        │            damping 0.85, ε 1e-6, max 100 iters
        ▼
   BlogSearch (HTTP) ◄── query                  Lexical:
                                                  BM25 (k1=1.2, b=0.75)
                                                  + α·log(PageRank), α=0.5
                                                  + tag-mask boost (title > heading > body)
                                                  + author-domain boost (×3.0)
                                                  + bigram phrase scoring (df ≥ 2)
                                                  + 7-pair synonym expansion
                                                  + recency decay
                                                  + per-domain diversification (0.85^k)
                                                Dense rerank top-50:
                                                  MiniLM-L6-v2, 384-dim cosine via DJL
```

There's a second pipeline (`Indexer` / `PageRank` / `Search` over the UCI ICS corpus, 55,385 docs / 2.8 GB) that I left untouched as a baseline. Both pipelines share `Tokenizer`, `Parser`, and the binary postings format. Only the per-doc metadata schema is different.

---

## Ranking notes

- **BM25** (k1=1.2, b=0.75) is the base score, with PageRank as a mild log-scaled boost on top.
- **Tag-mask multiplier** weights title, heading, and anchor matches higher than body matches.
- **Author-domain boost** (×3.0) when a known-author name is in the query and the result is on their domain.
- **Bigrams, synonyms, recency, and per-domain diversification** all layer onto the lexical score.
- **Dense rerank** of the top 50 by cosine similarity against `all-MiniLM-L6-v2` embeddings (384-dim, L2-normalized), via DJL locally.
- **Tuning loop**: every eval run archives `manifest.json` (configs + git SHA) and `metrics.json` to `eval/runs/<stamp>/`, and `evaluate.py` prints a config diff so any score change is attributable to a commit. LLM judgments are cached in `eval/qrels.json`.

---

## Stack

| Layer | Tech |
|---|---|
| Language | **Java 17**, Maven |
| Backend | Jsoup (HTML), Rome (RSS/Atom), OpenNLP + Snowball (tokenize/stem), custom binary inverted index |
| Frontend | HTML/JS served from the JVM |
| Ranking | **BM25 + PageRank** + tag mask + author boost + bigrams + synonyms + recency + diversification, then **dense rerank** with **`all-MiniLM-L6-v2`** via **DJL** |
| Eval | Python, **OpenAI `gpt-4o-mini`** as judge, matplotlib |

---

## Run it locally

```bash
# Smoke crawl: 3 blogs, 2 posts each
mvn exec:java -Dexec.mainClass="com.example.Crawler" -Dexec.args="3 2"

# Full crawl, all 446 feeds (~30–60 min)
mvn exec:java -Dexec.mainClass="com.example.Crawler"

# Build the inverted index
mvn exec:java -Dexec.mainClass="com.example.BlogIndexer"

# Run PageRank, rewrites blog_doc_meta.txt with the new field
mvn exec:java -Dexec.mainClass="com.example.BlogPageRank"

# Serve queries on http://localhost:8080
mvn exec:java -Dexec.mainClass="com.example.BlogSearch"
```

On Windows, prefix with `MAVEN_OPTS="-Xmx256m -Xms32m -XX:+UseSerialGC"` to avoid G1GC's `paging file is too small` startup error.
