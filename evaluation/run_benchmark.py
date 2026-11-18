"""Run the retrieval benchmark against a built knowledge database.

Usage:
    python -m evaluation.run_benchmark              # all questions
    python -m evaluation.run_benchmark --set dev    # only the tuning set
    python -m evaluation.run_benchmark --failures   # list every miss

Scoring
- Answerable questions: top-1 is correct when the search is confident and its first
  result is an expected article. Top-3 counts any expected article in the first three
  results, confident or not (what the user would see on screen).
- Unanswerable questions: correct when the search is NOT confident.
- Dangerous answer: a confident first result that is wrong and high-risk, or a confident
  answer at all to a question we have no article for. These should be as close to zero
  as possible, and a change that increases them should not be merged.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import yaml

from retrieval.search import DEFAULT_DB, DEFAULT_THRESHOLD, Searcher

QUESTIONS = Path(__file__).resolve().parent / "questions.yaml"


def run(db: Path, threshold: float, which: str | None) -> dict:
    qs = yaml.safe_load(QUESTIONS.read_text(encoding="utf-8"))["questions"]
    if which:
        qs = [q for q in qs if q["set"] == which]
    s = Searcher(db, threshold)
    rows = []
    t0 = time.perf_counter()
    for q in qs:
        r = s.search(q["q"], limit=3)
        ids = [m.id for m in r.matches]
        expect = q["expect"]
        if expect:
            top1 = r.confident and ids[0] in expect
            top3 = any(i in expect for i in ids)
            dangerous = r.confident and not top1 and r.matches[0].high_risk
            ok = top1
        else:
            top1 = top3 = None
            dangerous = r.confident
            ok = not r.confident
        rows.append({**q, "ids": ids, "confident": r.confident, "ok": ok, "top1": top1,
                     "top3": top3, "dangerous": dangerous,
                     "coverage": r.matches[0].coverage if r.matches else 0.0})
    elapsed = (time.perf_counter() - t0) / max(len(qs), 1) * 1000
    s.close()
    return {"rows": rows, "ms_per_query": elapsed}


def summarise(rows: list[dict]) -> dict:
    ans = [r for r in rows if r["expect"]]
    una = [r for r in rows if not r["expect"]]
    pct = lambda n, d: (100.0 * n / d) if d else float("nan")
    return {
        "questions": len(rows),
        "top1": pct(sum(r["top1"] for r in ans), len(ans)),
        "top3": pct(sum(r["top3"] for r in ans), len(ans)),
        "no_match_correct": pct(sum(r["ok"] for r in una), len(una)),
        "dangerous": sum(r["dangerous"] for r in rows),
        "n_ans": len(ans),
        "n_una": len(una),
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--set", choices=["dev", "heldout"])
    ap.add_argument("--failures", action="store_true", help="list every miss")
    args = ap.parse_args(argv)

    if not args.db.exists():
        print(f"{args.db} not found; run `python -m tools.build_kb` first.", file=sys.stderr)
        return 1
    out = run(args.db, args.threshold, args.set)
    rows = out["rows"]
    sets = [args.set] if args.set else ["dev", "heldout"]
    print(f"Threshold {args.threshold:.2f}, {out['ms_per_query']:.1f} ms/query\n")
    print(f"{'set':<9}{'n':>4}{'top-1':>9}{'top-3':>9}{'no-match':>10}{'dangerous':>11}")
    for name in sets + (["all"] if len(sets) > 1 else []):
        sub = rows if name == "all" else [r for r in rows if r["set"] == name]
        m = summarise(sub)
        print(f"{name:<9}{m['questions']:>4}{m['top1']:>8.0f}%{m['top3']:>8.0f}%"
              f"{m['no_match_correct']:>9.0f}%{m['dangerous']:>11}")

    misses = [r for r in rows if not r["ok"]]
    if args.failures and misses:
        print("\nMisses:")
        for r in misses:
            want = ", ".join(r["expect"]) or "(no match)"
            got = ", ".join(r["ids"]) or "(nothing)"
            tag = " DANGEROUS" if r["dangerous"] else ""
            conf = "confident" if r["confident"] else "not confident"
            print(f"  [{r['set']}] {r['q']!r}\n      want {want}; got {got} ({conf}, "
                  f"coverage {r['coverage']:.0%}){tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
