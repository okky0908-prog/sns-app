#!/usr/bin/env python3
"""テストのカバレッジを、テスト計画書（docs/test-plan.md「テストの層」）の層ごとに集計して Markdown の表で出す。

使い方（リポジトリ直下で。先に ./gradlew test と npm run test:coverage を実行しておく）:
    python3 scripts/coverage-summary.py backend/build/reports/jacoco/test/jacocoTestReport.csv
    python3 scripts/coverage-summary.py frontend/coverage/coverage-summary.json

- 分岐網羅（C1）は「分岐の両側を通った割合」、命令網羅（C0）は「命令（文）を通った割合」
- バックエンドの Mapper はインターフェースだけで SQL は XML にあるので、JaCoCo では測れない（ここには出ない）
- 数字は「通ったか」だけを表す。境界値やエラーの組み合わせを確かめたかは表さないので、ケースはテスト計画書の技法で設計する
"""

import csv
import json
import re
import sys
from collections import defaultdict

# (層の名前, その層かを判定する関数)。上から順に当てはめる
BACKEND_LAYERS = [
    ("Controller", lambda pkg, cls: cls.endswith("Controller")),
    ("Service", lambda pkg, cls: cls.endswith("Service")),
    ("認証の仕組み（フィルター・設定）", lambda pkg, cls: pkg.endswith(".auth")),
    ("エラー処理・共通（web）", lambda pkg, cls: pkg.endswith(".web")),
    ("画像の保存（storage）", lambda pkg, cls: pkg.endswith(".storage")),
    ("その他（データの入れ物など）", lambda pkg, cls: True),
]

FRONTEND_LAYERS = [
    ("関数（lib/・auth/ の .ts）", lambda f: re.search(r"/src/(lib|auth)/[^/]+\.ts$", f)),
    ("通信（api/）", lambda f: "/src/api/" in f),
    ("UI 部品（components/）", lambda f: "/src/components/" in f),
    ("画面（pages/）", lambda f: "/src/pages/" in f),
    ("その他（フック・認証の部品・posts/ など）", lambda f: True),
]


def percent(covered, total):
    return "—" if total == 0 else f"{covered * 100 / total:.1f}%"


def print_table(layers, sums, extra_header=None):
    header = "| 層 |" + (f" {extra_header} |" if extra_header else "") + " 命令網羅（C0） | 分岐網羅（C1） | 通っていない分岐 |"
    print(header)
    print("|---" * (header.count("|") - 1) + "|")
    for name in [n for n, _ in layers] + ["合計"]:
        if name not in sums:
            continue
        s = sums[name]
        label = f"**{name}**" if name == "合計" else name
        extra = f" {s['files']}（{s['tested']}） |" if extra_header else ""
        print(f"| {label} |{extra} {percent(s['sc'], s['st'])} | {percent(s['bc'], s['bt'])} | {s['bt'] - s['bc']} / {s['bt']} |")


def new_sum():
    return {"sc": 0, "st": 0, "bc": 0, "bt": 0, "files": 0, "tested": 0}


def backend(path):
    sums = defaultdict(new_sum)
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            name = next(n for n, matches in BACKEND_LAYERS if matches(row["PACKAGE"], row["CLASS"]))
            im, ic, bm, bc = (int(row[k]) for k in ("INSTRUCTION_MISSED", "INSTRUCTION_COVERED", "BRANCH_MISSED", "BRANCH_COVERED"))
            for s in (sums[name], sums["合計"]):
                s["sc"] += ic
                s["st"] += im + ic
                s["bc"] += bc
                s["bt"] += bm + bc
    print_table(BACKEND_LAYERS, sums)


def frontend(path):
    sums = defaultdict(new_sum)
    with open(path, encoding="utf-8") as f:
        summary = json.load(f)
    for file, m in summary.items():
        if file == "total":
            continue
        name = next(n for n, matches in FRONTEND_LAYERS if matches(file))
        for s in (sums[name], sums["合計"]):
            s["sc"] += m["statements"]["covered"]
            s["st"] += m["statements"]["total"]
            s["bc"] += m["branches"]["covered"]
            s["bt"] += m["branches"]["total"]
            s["files"] += 1
            s["tested"] += 1 if m["statements"]["covered"] > 0 else 0
    print_table(FRONTEND_LAYERS, sums, extra_header="ファイル数（テストあり）")


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "backend/build/reports/jacoco/test/jacocoTestReport.csv"
    frontend(target) if target.endswith(".json") else backend(target)
