"""Write the Python search's results for every benchmark question (plus a few edge cases)
to a TSV file. The Kotlin port's parity test checks that it returns exactly the same.

Usage:
    python -m evaluation.export_expected [--db build/knowledge.db] [--out build/expected_search.tsv]

Columns: query, confident (0/1), result ids (comma-separated), corrections (a>b;c>d)
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml

from retrieval.search import DEFAULT_DB, Searcher

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = Path(__file__).resolve().parent / "questions.yaml"
EXTRA = [
    "", "how do I", "BLEEDING!!!", "Someone’s shivering", "bleding wont stop",
    "power cut", "the lights are out", "no bars on my phone", "123", "généralement froid",
    "carbon monoxide", "co alarm beeping", "hypotermia", "rescued",
]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--out", type=Path, default=ROOT / "build" / "expected_search.tsv")
    args = ap.parse_args(argv)

    qs = [q["q"] for q in yaml.safe_load(QUESTIONS.read_text(encoding="utf-8"))["questions"]] + EXTRA
    s = Searcher(args.db)
    lines = []
    for q in qs:
        r = s.search(q, limit=3)
        corr = ";".join(f"{a}>{b}" for a, b in sorted(r.corrections.items()))
        lines.append("\t".join([q, "1" if r.confident else "0", ",".join(m.id for m in r.matches), corr]))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(lines)} expected results to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
