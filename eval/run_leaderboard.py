#!/usr/bin/env python3
"""
eval/run_leaderboard.py — offline NDCG/MRR/Recall leaderboard over qrels.json + results.json.

Grading rubric (set by LLM judge in evaluate.py, optionally validated by grade_dual.py):
  0 = Irrelevant — document does not address the query topic
  1 = Marginal   — mentions the topic but lacks engineering depth
  2 = Relevant   — covers the topic with reasonable depth
  3 = Highly relevant — directly addresses the query with engineering depth

Inter-rater reliability goal: Cohen's κ ≥ 0.6 across ≥ 10 dual-judged (query, URL) pairs.
Run grade_dual.py to measure κ against a human second-rater.

Metrics computed per query (only queries that have ≥ 1 qrel entry):
  NDCG@10   — standard discounted cumulative gain, ideal built from all qrel grades (max=3)
  MRR       — reciprocal rank of the first result with grade ≥ 1
  Recall@10 — fraction of grade≥2 qrel URLs found in the top-10 results.
              NOTE: results.json currently contains only top-10 per query, so the
              denominator is all grade≥2 URLs in qrels regardless of rank cap;
              rename the metric to Recall@20 once BlogSearch emits top-20.

Usage:
  python eval/run_leaderboard.py [--results eval/results.json] [--qrels eval/qrels.json]

Writes eval/leaderboard.json and prints a summary table to stdout.
"""

import argparse
import json
import math
import os
import sys
from pathlib import Path

QUERIES_FILE     = "eval/queries.json"
RESULTS_FILE     = "eval/results.json"
QRELS_FILE       = "eval/qrels.json"
MANIFEST_FILE    = "eval/manifest.json"
LEADERBOARD_FILE = "eval/leaderboard.json"

NDCG_K      = 10
MRR_THRESH  = 1   # grade >= this counts as a hit for MRR
RECALL_THRESH = 2  # grade >= this counts toward Recall denominator/numerator


# ── Metric helpers ────────────────────────────────────────────────────────────

def dcg(scores: list[float]) -> float:
    return sum(s / math.log2(i + 2) for i, s in enumerate(scores))


def ndcg_at_k(rel_scores: list[int], k: int) -> float:
    actual = dcg(rel_scores[:k])
    ideal  = dcg(sorted(rel_scores, reverse=True)[:k])
    return actual / ideal if ideal > 0 else 0.0


def mrr(rel_scores: list[int], threshold: int = MRR_THRESH) -> float:
    for i, s in enumerate(rel_scores):
        if s >= threshold:
            return 1.0 / (i + 1)
    return 0.0


def recall_at_k(result_urls: list[str], qrel: dict[str, int], k: int,
                threshold: int = RECALL_THRESH) -> float:
    """Fraction of grade>=threshold qrel URLs that appear in the top-k results."""
    relevant_set = {url for url, grade in qrel.items() if grade >= threshold}
    if not relevant_set:
        return 0.0
    hits = sum(1 for url in result_urls[:k] if url in relevant_set)
    return hits / len(relevant_set)


# ── Config name ───────────────────────────────────────────────────────────────

def pipeline_name(manifest_path: str) -> str:
    if os.path.exists(manifest_path):
        try:
            manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
            mode = manifest.get("ranking", {}).get("RERANKER_MODE")
            if mode:
                return str(mode)
        except Exception:
            pass
    return "BM25 baseline"


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Compute offline IR leaderboard.")
    parser.add_argument("--results",  default=RESULTS_FILE)
    parser.add_argument("--qrels",    default=QRELS_FILE)
    parser.add_argument("--queries",  default=QUERIES_FILE)
    parser.add_argument("--manifest", default=MANIFEST_FILE)
    parser.add_argument("--output",   default=LEADERBOARD_FILE)
    args = parser.parse_args()

    for path in (args.results, args.qrels):
        if not os.path.exists(path):
            print(f"ERROR: {path} not found.", file=sys.stderr)
            sys.exit(1)

    qrels:   dict[str, dict[str, int]] = json.loads(Path(args.qrels).read_text(encoding="utf-8"))
    results: list[dict]                = json.loads(Path(args.results).read_text(encoding="utf-8"))

    # Build query→category map
    category_map: dict[str, str] = {}
    if os.path.exists(args.queries):
        for q in json.loads(Path(args.queries).read_text(encoding="utf-8")):
            category_map[q["query"]] = q.get("category", "UNKNOWN")

    name = pipeline_name(args.manifest)

    per_query: list[dict] = []

    for entry in results:
        query    = entry["query"]
        category = entry.get("category") or category_map.get(query, "UNKNOWN")

        # Skip queries with no qrel entries
        qrel = qrels.get(query)
        if not qrel:
            continue

        # Skip negative/off-corpus — they don't contribute to ranking metrics
        if category == "NEGATIVE / OFF-CORPUS":
            continue

        ranked_urls = [r["url"] for r in entry.get("results", [])]
        # Build relevance score list aligned to the ranked URL order
        rel_scores  = [qrel.get(url, 0) for url in ranked_urls]

        ndcg  = ndcg_at_k(rel_scores, NDCG_K)
        mrr_v = mrr(rel_scores)
        rec   = recall_at_k(ranked_urls, qrel, k=NDCG_K)   # capped at top-10 for now

        per_query.append({
            "query":     query,
            "category":  category,
            "ndcg10":    round(ndcg,  4),
            "mrr":       round(mrr_v, 4),
            "recall10":  round(rec,   4),
        })

    if not per_query:
        print("No queries with qrel entries found. Run evaluate.py first to populate qrels.json.",
              file=sys.stderr)
        sys.exit(1)

    # ── Aggregate overall ──────────────────────────────────────────────────────
    n = len(per_query)
    overall = {
        "ndcg10":   round(sum(m["ndcg10"]  for m in per_query) / n, 4),
        "mrr":      round(sum(m["mrr"]     for m in per_query) / n, 4),
        "recall10": round(sum(m["recall10"] for m in per_query) / n, 4),
        "n_queries": n,
    }

    # ── Aggregate by category ──────────────────────────────────────────────────
    by_cat: dict[str, list[dict]] = {}
    for m in per_query:
        by_cat.setdefault(m["category"], []).append(m)

    by_category: dict[str, dict] = {}
    for cat, ms in sorted(by_cat.items()):
        nc = len(ms)
        by_category[cat] = {
            "ndcg10":    round(sum(m["ndcg10"]   for m in ms) / nc, 4),
            "mrr":       round(sum(m["mrr"]       for m in ms) / nc, 4),
            "recall10":  round(sum(m["recall10"]  for m in ms) / nc, 4),
            "n_queries": nc,
        }

    # ── Build leaderboard entry ────────────────────────────────────────────────
    config_entry = {
        "name":        name,
        "ndcg10":      overall["ndcg10"],
        "mrr":         overall["mrr"],
        "recall10":    overall["recall10"],
        "n_queries":   n,
        "by_category": by_category,
    }

    # Merge with existing leaderboard.json (replace entry with same name if present)
    leaderboard: dict = {"configs": []}
    if os.path.exists(args.output):
        try:
            leaderboard = json.loads(Path(args.output).read_text(encoding="utf-8"))
        except Exception:
            pass

    configs = leaderboard.get("configs", [])
    configs = [c for c in configs if c.get("name") != name]
    configs.append(config_entry)
    leaderboard["configs"] = configs

    Path(args.output).write_text(json.dumps(leaderboard, indent=2), encoding="utf-8")

    # ── Print table ────────────────────────────────────────────────────────────
    col_name = max(len(c["name"]) for c in configs)
    col_name = max(col_name, 17)
    header = f"{'Pipeline':<{col_name}}  {'NDCG@10':>7}  {'MRR':>6}  {'Recall@10':>9}  {'Queries':>7}"
    sep    = f"{'─'*col_name}  {'─'*7}  {'─'*6}  {'─'*9}  {'─'*7}"
    print(header)
    print(sep)
    for c in configs:
        print(f"{c['name']:<{col_name}}  {c['ndcg10']:>7.3f}  {c['mrr']:>6.3f}"
              f"  {c['recall10']:>9.3f}  {c['n_queries']:>7}")

    print(f"\nPer-category breakdown for '{name}':")
    cat_hdr = f"  {'Category':<30}  {'NDCG@10':>7}  {'MRR':>6}  {'Recall@10':>9}  {'n':>4}"
    print(cat_hdr)
    print("  " + "─" * (len(cat_hdr) - 2))
    for cat, agg in by_category.items():
        print(f"  {cat:<30}  {agg['ndcg10']:>7.3f}  {agg['mrr']:>6.3f}"
              f"  {agg['recall10']:>9.3f}  {agg['n_queries']:>4}")

    print(f"\nLeaderboard written to {args.output}")


if __name__ == "__main__":
    main()
