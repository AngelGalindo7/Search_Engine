#!/usr/bin/env python3
"""
Blog search engine evaluation.

Workflow:
  1. mvn exec:java -Dexec.mainClass=com.example.BlogSearch -Dsearch.mode=eval
     -> writes eval/results.json

  2. python eval/evaluate.py
     -> judges each result with GPT-4o-mini (cached in eval/qrels.json), prints
        metrics, writes eval/metrics.json, archives both files to
        eval/runs/<timestamp>/, prints delta vs previous run.

Requires: OPENAI_API_KEY in environment.
Cost estimate: ~$0.04 for 44 queries x 10 results at gpt-4o-mini pricing,
amortized to ~$0 on subsequent runs that hit the qrels cache.
"""

import datetime
import json
import math
import os
import shutil
import sys
from pathlib import Path
from dotenv import load_dotenv
from openai import OpenAI

load_dotenv()  # reads .env from project root

RESULTS_FILE        = "eval/results.json"
QUERIES_FILE        = "eval/queries.json"
METRICS_FILE        = "eval/metrics.json"
MANIFEST_FILE       = "eval/manifest.json"
QRELS_FILE          = "eval/qrels.json"
RUNS_DIR            = Path("eval/runs")
TOPN                = 10
JUDGE_MODEL         = "gpt-4o-mini"
RELEVANCE_THRESHOLD = 1  # score >= this counts as a hit for P@k


# ── Qrels cache ───────────────────────────────────────────────────────────────
# Persisted (query, url) -> score map. Bootstrapped by the LLM judge but reused
# across runs so identical result sets get identical scores -- the delta
# between two ranker variants becomes pure ranking signal, not judge noise.

def load_qrels():
    if not os.path.exists(QRELS_FILE):
        return {}
    with open(QRELS_FILE, encoding="utf-8") as f:
        return json.load(f)

def save_qrels(qrels):
    with open(QRELS_FILE, "w", encoding="utf-8") as f:
        json.dump(qrels, f, indent=2, sort_keys=True)

def judge_with_cache(client, qrels, stats, query, relevance_notes, title, company, url):
    bucket = qrels.setdefault(query, {})
    if url in bucket:
        stats["hits"] += 1
        return bucket[url]
    score = judge_relevance(client, query, relevance_notes, title, company, url)
    bucket[url] = score
    stats["new"] += 1
    return score


# ── OpenAI judge ──────────────────────────────────────────────────────────────

def judge_relevance(client, query, relevance_notes, title, company, url):
    prompt = (
        f'You are an expert relevance assessor for engineering blog posts.\n\n'
        f'Query: "{query}"\n'
        f'Document title: "{title}"\n'
        f'Source blog: {company}\n'
        f'URL: {url}\n\n'
        f'Relevance criteria: {relevance_notes}\n\n'
        f'Rate the relevance of this document to the query on a scale of 0–3:\n'
        f'  0 = Not relevant\n'
        f'  1 = Marginally relevant (mentions the topic, lacks depth)\n'
        f'  2 = Relevant (covers the topic with reasonable depth)\n'
        f'  3 = Highly relevant (directly addresses the query with engineering depth)\n\n'
        f'Respond with only a single integer: 0, 1, 2, or 3.'
    )
    response = client.chat.completions.create(
        model=JUDGE_MODEL,
        messages=[{"role": "user", "content": prompt}],
        temperature=0,
        max_tokens=1,
    )
    raw = response.choices[0].message.content.strip()
    try:
        return max(0, min(3, int(raw)))
    except ValueError:
        return 0


# ── IR metrics ────────────────────────────────────────────────────────────────

def dcg(scores):
    return sum(s / math.log2(i + 2) for i, s in enumerate(scores))

def ndcg_at_k(rel_scores, k):
    actual = dcg(rel_scores[:k])
    ideal  = dcg(sorted(rel_scores, reverse=True)[:k])
    return actual / ideal if ideal > 0 else 0.0

def mrr(rel_scores):
    for i, s in enumerate(rel_scores):
        if s > 0:
            return 1.0 / (i + 1)
    return 0.0

def precision_at_k(rel_scores, k):
    hits = sum(1 for s in rel_scores[:k] if s >= RELEVANCE_THRESHOLD)
    return hits / k


# ── Run archive ───────────────────────────────────────────────────────────────

def archive_run(stamp, all_metrics):
    """Copy results.json + manifest.json + write metrics.json into eval/runs/<stamp>/."""
    run_dir = RUNS_DIR / stamp
    run_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(RESULTS_FILE, run_dir / "results.json")
    if os.path.exists(MANIFEST_FILE):
        # Augment manifest with eval-side config that BlogSearch doesn't know about
        manifest = json.load(open(MANIFEST_FILE, encoding="utf-8"))
        manifest["evaluator"] = {
            "judge_model":         JUDGE_MODEL,
            "relevance_threshold": RELEVANCE_THRESHOLD,
            "topn":                TOPN,
        }
        with open(run_dir / "manifest.json", "w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2)
    with open(run_dir / "metrics.json", "w", encoding="utf-8") as f:
        json.dump(all_metrics, f, indent=2)
    print(f"Run archived → {run_dir}/")


def print_manifest_diff(prev_stamp, curr_stamp):
    """Show what changed in the configuration between the two runs."""
    prev_path = RUNS_DIR / prev_stamp / "manifest.json"
    curr_path = RUNS_DIR / curr_stamp / "manifest.json"
    if not prev_path.exists() or not curr_path.exists():
        return
    prev = json.loads(prev_path.read_text(encoding="utf-8"))
    curr = json.loads(curr_path.read_text(encoding="utf-8"))

    def flatten(obj, prefix=""):
        out = {}
        for k, v in obj.items():
            key = f"{prefix}{k}"
            if isinstance(v, dict):
                out.update(flatten(v, key + "."))
            else:
                out[key] = v
        return out

    p, c = flatten(prev), flatten(curr)
    changes = []
    for k in sorted(set(p) | set(c)):
        pv, cv = p.get(k, "<missing>"), c.get(k, "<missing>")
        if pv != cv and k != "timestamp":
            changes.append((k, pv, cv))
    if changes:
        print(f"\n{'='*70}")
        print("CONFIG CHANGES vs PREVIOUS RUN")
        print(f"{'='*70}")
        for k, pv, cv in changes:
            print(f"  {k:<28}  {pv}  →  {cv}")


def latest_previous_run(current_stamp):
    """Return (stamp, metrics) for the most recent archived run before this one, or (None, [])."""
    if not RUNS_DIR.exists():
        return None, []
    runs = sorted(d.name for d in RUNS_DIR.iterdir() if d.is_dir() and d.name != current_stamp)
    if not runs:
        return None, []
    prev = RUNS_DIR / runs[-1] / "metrics.json"
    if not prev.exists():
        return None, []
    try:
        data = json.loads(prev.read_text(encoding="utf-8"))
        print(f"Comparing against previous run: {runs[-1]}")
        return runs[-1], data
    except Exception:
        return None, []


# ── Delta summary ─────────────────────────────────────────────────────────────

def print_delta(prev_metrics, curr_metrics):
    prev = {m["query"]: m for m in prev_metrics if "ndcg@10" in m}
    curr = {m["query"]: m for m in curr_metrics if "ndcg@10" in m}

    improvements, regressions = [], []
    for q, cm in curr.items():
        if q not in prev:
            continue
        delta = cm["ndcg@10"] - prev[q]["ndcg@10"]
        if abs(delta) >= 0.01:
            (improvements if delta > 0 else regressions).append((q, delta))

    improvements.sort(key=lambda x: -x[1])
    regressions.sort(key=lambda x: x[1])

    print(f"\n{'='*70}")
    print("DELTA vs PREVIOUS RUN  (NDCG@10, threshold ±0.01)")
    print(f"{'='*70}")
    if improvements:
        print("▲ Improvements:")
        for q, d in improvements:
            print(f"  {q:<45} {d:+.3f}")
    if regressions:
        print("▼ Regressions:")
        for q, d in regressions:
            print(f"  {q:<45} {d:+.3f}")
    if not improvements and not regressions:
        print("  No significant change vs previous run (all |Δ| < 0.01)")

    # Overall delta
    prev_pos = [m for m in prev_metrics if "ndcg@10" in m]
    curr_pos = [m for m in curr_metrics if "ndcg@10" in m]
    if prev_pos and curr_pos:
        prev_avg = sum(m["ndcg@10"] for m in prev_pos) / len(prev_pos)
        curr_avg = sum(m["ndcg@10"] for m in curr_pos) / len(curr_pos)
        print(f"\n  Overall NDCG@10: {prev_avg:.3f} → {curr_avg:.3f}  ({curr_avg - prev_avg:+.3f})")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    for path in (RESULTS_FILE, QUERIES_FILE):
        if not os.path.exists(path):
            print(f"ERROR: {path} not found.", file=sys.stderr)
            print("Run: mvn exec:java -Dexec.mainClass=com.example.BlogSearch -Dsearch.mode=eval",
                  file=sys.stderr)
            sys.exit(1)

    stamp = datetime.datetime.now().strftime("%Y-%m-%dT%H-%M")

    # Load previous run before anything is overwritten
    prev_stamp, prev_metrics = latest_previous_run(stamp)

    client  = OpenAI()
    queries = {q["query"]: q for q in json.load(open(QUERIES_FILE, encoding="utf-8"))}
    results = json.load(open(RESULTS_FILE, encoding="utf-8"))
    qrels   = load_qrels()
    stats   = {"hits": 0, "new": 0}

    all_metrics = []
    by_category = {}

    for entry in results:
        query    = entry["query"]
        category = entry.get("category", "UNKNOWN")
        q_meta   = queries.get(query, {})
        notes    = q_meta.get("relevance_notes", "Use your best judgment.")
        is_neg   = category == "NEGATIVE / OFF-CORPUS"

        print(f"\n{'─'*70}")
        print(f"Query : {query}")
        print(f"Cat   : {category}")

        rel_scores = []
        for r in entry["results"][:TOPN]:
            if is_neg:
                rel   = 0
                label = "FORCED 0 (negative)"
            else:
                # rel = judge_relevance(client, query, notes, r["title"], r["company"], r["url"])
                rel   = judge_with_cache(client, qrels, stats, query, notes,
                                         r["title"], r["company"], r["url"])
                label = f"{rel}/3"
            rel_scores.append(rel)
            print(f"  {r['rank']:2}. [{label}] {r['title'][:60]} — {r['company']}")

        if is_neg:
            spurious = sum(1 for s in rel_scores if s >= RELEVANCE_THRESHOLD)
            m = {
                "query":         query,
                "category":      category,
                "spurious_hits": spurious,
                "passed":        spurious == 0,
            }
            print(f"  => spurious hits: {spurious}  {'PASS' if m['passed'] else 'FAIL'}")
        else:
            m = {
                "query":      query,
                "category":   category,
                "ndcg@10":    round(ndcg_at_k(rel_scores, 10), 4),
                "mrr":        round(mrr(rel_scores), 4),
                "p@5":        round(precision_at_k(rel_scores, 5), 4),
                "p@10":       round(precision_at_k(rel_scores, 10), 4),
                "rel_scores": rel_scores,
            }
            print(f"  => NDCG@10={m['ndcg@10']:.3f}  MRR={m['mrr']:.3f}"
                  f"  P@5={m['p@5']:.2f}  P@10={m['p@10']:.2f}")

        all_metrics.append(m)
        by_category.setdefault(category, []).append(m)

    # ── Summary table ──────────────────────────────────────────────────────────
    print(f"\n\n{'='*70}")
    print("RESULTS BY CATEGORY")
    print(f"{'='*70}")
    fmt = "{:<30}  {:>5}  {:>8}  {:>6}  {:>6}  {:>6}"
    print(fmt.format("Category", "n", "NDCG@10", "MRR", "P@5", "P@10"))
    print("─" * 70)

    positive_all = [m for m in all_metrics if m["category"] != "NEGATIVE / OFF-CORPUS"]

    for cat in sorted(by_category):
        ms  = by_category[cat]
        pos = [m for m in ms if m["category"] != "NEGATIVE / OFF-CORPUS"]
        if not pos:
            neg_pass = sum(1 for m in ms if m.get("passed"))
            print(fmt.format(cat[:30], f"{neg_pass}/{len(ms)}", "—", "—", "—", "—") + "  (neg)")
            continue
        print(fmt.format(
            cat[:30], len(pos),
            f"{sum(m['ndcg@10'] for m in pos)/len(pos):.3f}",
            f"{sum(m['mrr']     for m in pos)/len(pos):.3f}",
            f"{sum(m['p@5']     for m in pos)/len(pos):.2f}",
            f"{sum(m['p@10']    for m in pos)/len(pos):.2f}",
        ))

    if positive_all:
        n = len(positive_all)
        print("─" * 70)
        print(fmt.format(
            "OVERALL", n,
            f"{sum(m['ndcg@10'] for m in positive_all)/n:.3f}",
            f"{sum(m['mrr']     for m in positive_all)/n:.3f}",
            f"{sum(m['p@5']     for m in positive_all)/n:.2f}",
            f"{sum(m['p@10']    for m in positive_all)/n:.2f}",
        ))

    # ── Save + archive ─────────────────────────────────────────────────────────
    with open(METRICS_FILE, "w", encoding="utf-8") as f:
        json.dump(all_metrics, f, indent=2)
    print(f"\nFull metrics saved to {METRICS_FILE}")

    total_judged = stats["hits"] + stats["new"]
    if total_judged:
        hit_pct = 100.0 * stats["hits"] / total_judged
        print(f"Qrels cache: {stats['hits']} hits / {stats['new']} new "
              f"({hit_pct:.0f}% reused) -> {QRELS_FILE}")
    if stats["new"] > 0:
        save_qrels(qrels)

    archive_run(stamp, all_metrics)

    # ── Delta vs previous run ──────────────────────────────────────────────────
    if prev_metrics:
        print_delta(prev_metrics, all_metrics)
        if prev_stamp:
            print_manifest_diff(prev_stamp, stamp)
    else:
        print("\n(No previous run to compare against — this is the baseline.)")


if __name__ == "__main__":
    main()
