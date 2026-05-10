#!/usr/bin/env python3
"""
eval/grade_dual.py — human vs LLM inter-rater agreement for qrels.json.

Randomly samples 10 (query, URL) pairs from qrels.json that already have an
LLM-assigned grade.  For each pair, the human is asked to assign a grade 0–3.
Cohen's κ is then computed between the two judge sets.

Grading rubric:
  0 = Irrelevant
  1 = Marginal   (mentions topic, lacks engineering depth)
  2 = Relevant   (covers topic with reasonable depth)
  3 = Highly relevant (directly addresses query with engineering depth)

Goal: κ ≥ 0.6 validates that the LLM grades are reliable enough to trust for
NDCG/MRR computation in run_leaderboard.py.

Usage:
  python eval/grade_dual.py [--qrels eval/qrels.json] [--results eval/results.json] [--seed 42]
"""

import argparse
import json
import math
import os
import random
import sys
from pathlib import Path

QRELS_FILE   = "eval/qrels.json"
RESULTS_FILE = "eval/results.json"
SAMPLE_SIZE  = 10


# ── Cohen's κ ─────────────────────────────────────────────────────────────────

def cohens_kappa(rater_a: list[int], rater_b: list[int], categories: list[int]) -> float:
    """
    Compute Cohen's κ for two paired rating lists.
    categories: all possible grade values (e.g. [0, 1, 2, 3]).
    """
    n = len(rater_a)
    if n == 0:
        return float("nan")

    # Observed agreement
    p_o = sum(1 for a, b in zip(rater_a, rater_b) if a == b) / n

    # Expected agreement
    p_e = 0.0
    for cat in categories:
        p_a = rater_a.count(cat) / n
        p_b = rater_b.count(cat) / n
        p_e += p_a * p_b

    if math.isclose(p_e, 1.0):
        return 1.0  # perfect agreement by chance — degenerate case
    return (p_o - p_e) / (1.0 - p_e)


# ── Title lookup ──────────────────────────────────────────────────────────────

def build_title_index(results_path: str) -> dict[str, str]:
    """Return url → title mapping from results.json."""
    if not os.path.exists(results_path):
        return {}
    index: dict[str, str] = {}
    try:
        for entry in json.loads(Path(results_path).read_text(encoding="utf-8")):
            for r in entry.get("results", []):
                index[r["url"]] = r.get("title", "")
    except Exception:
        pass
    return index


# ── Human prompt ─────────────────────────────────────────────────────────────

def ask_grade(query: str, url: str, title: str) -> int:
    print(f"\n{'─'*70}")
    print(f"  Query : {query}")
    print(f"  URL   : {url}")
    if title:
        print(f"  Title : {title}")
    print()
    print("  Grade 0–3:")
    print("    0 = Irrelevant")
    print("    1 = Marginal (mentions topic, no depth)")
    print("    2 = Relevant (covers topic with depth)")
    print("    3 = Highly relevant (directly addresses query)")
    while True:
        try:
            raw = input("  Your grade: ").strip()
            grade = int(raw)
            if 0 <= grade <= 3:
                return grade
            print("  Please enter 0, 1, 2, or 3.")
        except (ValueError, EOFError):
            print("  Please enter 0, 1, 2, or 3.")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Human vs LLM dual-grading for κ computation.")
    parser.add_argument("--qrels",   default=QRELS_FILE)
    parser.add_argument("--results", default=RESULTS_FILE)
    parser.add_argument("--n",       type=int, default=SAMPLE_SIZE,
                        help="Number of (query, URL) pairs to sample (default: 10)")
    parser.add_argument("--seed",    type=int, default=None,
                        help="Random seed for reproducible sampling")
    args = parser.parse_args()

    if not os.path.exists(args.qrels):
        print(f"ERROR: {args.qrels} not found. Run evaluate.py first.", file=sys.stderr)
        sys.exit(1)

    qrels: dict[str, dict[str, int]] = json.loads(Path(args.qrels).read_text(encoding="utf-8"))
    title_index = build_title_index(args.results)

    # Flatten to (query, url, llm_grade) triples, skipping negative queries
    pairs: list[tuple[str, str, int]] = []
    for query, url_grades in qrels.items():
        for url, grade in url_grades.items():
            pairs.append((query, url, grade))

    if len(pairs) < args.n:
        print(f"WARNING: Only {len(pairs)} graded pairs in qrels; sampling all of them.",
              file=sys.stderr)
        args.n = len(pairs)

    rng = random.Random(args.seed)
    sample = rng.sample(pairs, args.n)

    print(f"Dual-grading {args.n} randomly sampled (query, URL) pairs.")
    print("For each, assign your own 0–3 grade without looking at the LLM grade.")
    print("κ will be revealed at the end.\n")

    llm_grades:   list[int] = []
    human_grades: list[int] = []

    for query, url, llm_grade in sample:
        title = title_index.get(url, "")
        human_grade = ask_grade(query, url, title)
        llm_grades.append(llm_grade)
        human_grades.append(human_grade)
        print(f"  (LLM grade was: {llm_grade})")

    kappa = cohens_kappa(llm_grades, human_grades, categories=[0, 1, 2, 3])

    print(f"\n{'='*70}")
    print("RESULTS")
    print(f"{'='*70}")
    print(f"  Pairs graded : {args.n}")
    print(f"  LLM grades   : {llm_grades}")
    print(f"  Human grades : {human_grades}")
    exact_agree = sum(1 for a, b in zip(llm_grades, human_grades) if a == b)
    print(f"  Exact agreement : {exact_agree}/{args.n} ({100*exact_agree/args.n:.0f}%)")
    print(f"  Cohen's κ    : {kappa:.3f}", end="  ")
    if kappa >= 0.8:
        print("(excellent)")
    elif kappa >= 0.6:
        print("(substantial — goal met)")
    elif kappa >= 0.4:
        print("(moderate — below goal)")
    else:
        print("(poor — LLM grades may be unreliable)")


if __name__ == "__main__":
    main()
