#!/usr/bin/env python3
"""k6 の結果の CSV（perf/run.sh が perf/results/ に残す .csv.gz）を、時間の区切りごとに集計する。

k6 の要約は全体の値しか出さないので、ストレス（人数ごとの p95）・耐久（時間がたつと遅くなるか、ヒープが増え続けるか）・
ログイン集中（CPU）は、これで時間ごとに見る（docs/performance-test-plan.md「7. 見る値と記録」）。

使い方:
  python3 perf/analyze.py <CSV> [--window 秒] [--scenario 名前]

  --window    区切りの長さ（既定 60 秒）
  --scenario  応答時間を集計するシナリオ（既定は monitor 以外の全部）。例：--scenario users

外部のライブラリは使わない（Python の標準ライブラリだけで動く）。
"""

import argparse
import csv
import gzip
import math
from collections import defaultdict

GAUGES = [
    ("backend_heap_used_mb", "ヒープ(MB)"),
    ("backend_gc_pause_total_ms", "GC累計(ms)"),
    ("backend_process_cpu", "CPU"),
    ("backend_hikari_active", "接続使用"),
    ("backend_hikari_pending", "接続待ち"),
]


def percentile(values, p):
    """最も近い順位の方法で百分位数を求める（k6 の p(95) と同じ考え方。値が少ないときは少しずれる）"""
    if not values:
        return float("nan")
    values = sorted(values)
    k = max(0, math.ceil(p / 100 * len(values)) - 1)
    return values[k]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv")
    parser.add_argument("--window", type=int, default=60)
    parser.add_argument("--scenario")
    args = parser.parse_args()

    opener = gzip.open if args.csv.endswith(".gz") else open
    durations = defaultdict(list)
    failed = defaultdict(lambda: [0, 0])
    vus = defaultdict(int)
    gauges = defaultdict(dict)
    start = None

    with opener(args.csv, "rt", newline="") as f:
        for row in csv.DictReader(f):
            t = float(row["timestamp"])
            if start is None:
                start = t
            w = int((t - start) // args.window)
            name = row["metric_name"]
            scenario = row.get("scenario", "")
            is_target = (scenario == args.scenario) if args.scenario else scenario != "monitor"
            if name == "http_req_duration" and is_target:
                durations[w].append(float(row["metric_value"]))
            elif name == "http_req_failed" and is_target:
                failed[w][0] += float(row["metric_value"])
                failed[w][1] += 1
            elif name == "vus":
                vus[w] = max(vus[w], int(float(row["metric_value"])))
            elif name in dict(GAUGES):
                gauges[w][name] = max(gauges[w].get(name, float("-inf")), float(row["metric_value"]))

    if start is None:
        print("データがありません")
        return

    target = args.scenario or "monitor 以外"
    print(f"区切り {args.window} 秒・応答時間の対象 {target}（VU は全シナリオの合計、metrics は区切りの中の最大値）")
    header = ["経過", "VU", "件数", "件/秒", "p50(ms)", "p95(ms)", "p99(ms)", "エラー率"] + [label for _, label in GAUGES]
    print("| " + " | ".join(header) + " |")
    print("|" + "---|" * len(header))
    for w in sorted(set(durations) | set(vus) | set(gauges)):
        d = durations.get(w, [])
        fail, total = failed.get(w, (0, 0))
        elapsed = w * args.window
        cells = [
            f"{elapsed // 60}:{elapsed % 60:02d}",
            str(vus.get(w, "")),
            str(len(d)),
            f"{len(d) / args.window:.1f}",
            f"{percentile(d, 50):.1f}",
            f"{percentile(d, 95):.1f}",
            f"{percentile(d, 99):.1f}",
            f"{fail / total * 100:.2f}%" if total else "",
        ]
        for name, _ in GAUGES:
            v = gauges[w].get(name)
            cells.append("" if v is None else (f"{v:.2f}" if name == "backend_process_cpu" else f"{v:.0f}"))
        print("| " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
