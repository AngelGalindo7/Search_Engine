#!/usr/bin/env python3
"""Render eval/metrics.json into eval/metrics.png — NDCG@10 by category."""

import json
from pathlib import Path

import matplotlib.pyplot as plt

METRICS_FILE = Path("eval/metrics.json")
OUT_FILE     = Path("eval/metrics.png")

def main():
    metrics = json.loads(METRICS_FILE.read_text(encoding="utf-8"))
    positive = [m for m in metrics if m["category"] != "NEGATIVE / OFF-CORPUS"]
    negative = [m for m in metrics if m["category"] == "NEGATIVE / OFF-CORPUS"]

    by_cat = {}
    for m in positive:
        by_cat.setdefault(m["category"], []).append(m["ndcg@10"])

    # Sort categories by mean NDCG descending so the chart reads best-to-worst.
    sorted_cats = sorted(by_cat.items(),
                         key=lambda kv: sum(kv[1]) / len(kv[1]),
                         reverse=True)
    labels = [f"{c}  (n={len(v)})" for c, v in sorted_cats]
    means  = [sum(v) / len(v) for _, v in sorted_cats]
    overall = sum(m["ndcg@10"] for m in positive) / len(positive)
    neg_pass = sum(1 for m in negative if m.get("passed"))

    fig, ax = plt.subplots(figsize=(10, 5.5))
    bars = ax.barh(labels, means, color="#4C78A8", edgecolor="#1f3b5c")
    ax.axvline(overall, color="#E45756", linestyle="--", linewidth=1.5,
               label=f"Overall mean = {overall:.3f}")

    for bar, mean in zip(bars, means):
        ax.text(mean + 0.01, bar.get_y() + bar.get_height() / 2,
                f"{mean:.3f}", va="center", fontsize=9)

    ax.set_xlim(0, 1.05)
    ax.set_xlabel("NDCG@10  (LLM-judged, gpt-4o-mini, 0–3 scale)")
    ax.set_title(f"Engineering Blog Search — NDCG@10 by query category"
                 f"   (negatives: {neg_pass}/{len(negative)} pass)")
    ax.invert_yaxis()
    ax.legend(loc="lower right", frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="x", linestyle=":", alpha=0.4)

    fig.tight_layout()
    fig.savefig(OUT_FILE, dpi=140)
    print(f"wrote {OUT_FILE}  (overall NDCG@10 = {overall:.3f}, n={len(positive)})")


if __name__ == "__main__":
    main()
