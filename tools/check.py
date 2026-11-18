"""Run every check on the knowledge base and print PASS or a numbered list of problems to fix.

Usage:
    python -m tools.check

This is the only command you need after adding or editing articles. Exit code 0 means PASS.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

from tools import build_kb

ROOT = Path(__file__).resolve().parent.parent
QUESTIONS = ROOT / "evaluation" / "questions.yaml"


def check_questions(article_ids: set[str], problems: list[str]) -> list[dict]:
    rel = QUESTIONS.relative_to(ROOT)
    try:
        data = yaml.safe_load(QUESTIONS.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        problems.append(f"{rel}: could not be read: {e}. Fix: each question must be on one line like "
                        '- {set: dev, q: "how do I ...", expect: [article-id]}')
        return []
    qs = data.get("questions") if isinstance(data, dict) else None
    if not isinstance(qs, list):
        problems.append(f"{rel}: must contain 'questions:' followed by a list.")
        return []
    seen = set()
    for i, q in enumerate(qs, 1):
        if not isinstance(q, dict) or set(q) != {"set", "q", "expect"}:
            problems.append(f"{rel}: question {i} must have exactly set, q and expect.")
            continue
        if q["set"] not in ("dev", "heldout"):
            problems.append(f"{rel}: question {i} ('{q['q']}'): set must be dev or heldout.")
        if not isinstance(q["q"], str) or not q["q"].strip():
            problems.append(f"{rel}: question {i} has an empty q.")
        elif q["q"].strip().lower() in seen:
            problems.append(f"{rel}: question {i} ('{q['q']}') is a duplicate. Fix: remove it.")
        else:
            seen.add(q["q"].strip().lower())
        if not isinstance(q["expect"], list):
            problems.append(f"{rel}: question {i} ('{q['q']}'): expect must be a list, e.g. [water-filtration] or [].")
            continue
        for aid in q["expect"]:
            if aid not in article_ids:
                problems.append(f"{rel}: question {i} ('{q['q']}') expects '{aid}', which is not an article id.")
    covered = {aid for q in qs if isinstance(q, dict) and q.get("set") == "dev"
               for aid in (q.get("expect") or []) if isinstance(q.get("expect"), list)}
    for aid in sorted(article_ids - covered):
        problems.append(f"{rel}: no dev question finds article '{aid}'. Fix: add at least 2 lines like "
                        f'- {{set: dev, q: "<how a person would ask>", expect: [{aid}]}}')
    return qs


def main() -> int:
    problems: list[str] = []
    print("Fallback Manual check\n")

    # 1. Articles
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / "knowledge.db"
        report = build_kb.build(out=db)
        problems += report.errors
        status = "PASS" if not report.errors else "FAIL"
        print(f"[{status}] articles are valid")

        # 2. Benchmark questions and search quality
        ids = {p.stem for p in build_kb.ARTICLES_DIR.glob("*/*.md")}
        before = len(problems)
        qs = check_questions(ids, problems)
        if not report.errors and qs and len(problems) == before:
            from evaluation.run_benchmark import run, summarise
            rows = run(db, 0.6, None)["rows"]
            for r in rows:
                if r["set"] == "dev" and not r["ok"]:
                    want = ", ".join(r["expect"]) or "no match"
                    got = r["ids"][0] if r["confident"] and r["ids"] else "no confident match"
                    problems.append(
                        f"search: dev question '{r['q']}' should find {want} but found {got}. Fix: add the words "
                        "from this question to that article's situations or keywords (or a group in "
                        "knowledge/synonyms.yaml). Do not change the question to make it pass.")
                if r["dangerous"]:
                    problems.append(f"search: '{r['q']}' gives a confident WRONG answer ({r['ids'][0]}). "
                                    "Fix: make that article's situations/keywords more specific.")
            m = summarise(rows)
            ok = len(problems) == before
            print(f"[{'PASS' if ok else 'FAIL'}] search finds the right article "
                  f"(dev top-1 {summarise([r for r in rows if r['set'] == 'dev'])['top1']:.0f}%, "
                  f"held-out top-1 {summarise([r for r in rows if r['set'] == 'heldout'])['top1']:.0f}%, "
                  f"dangerous answers {m['dangerous']})")
        else:
            print(f"[{'FAIL' if len(problems) > before else 'SKIP'}] search finds the right article"
                  + ("" if len(problems) > before else " (fix the article problems first)"))

    # 3. Unit tests (only meaningful once the articles themselves are valid)
    if problems:
        print("[SKIP] unit tests (fix the problems below first)")
    elif importlib.util.find_spec("pytest"):
        r = subprocess.run([sys.executable, "-m", "pytest", "-q", "-p", "no:cacheprovider"],
                           cwd=ROOT, capture_output=True, text=True)
        print(f"[{'PASS' if r.returncode == 0 else 'FAIL'}] unit tests")
        if r.returncode != 0:
            tail = "\n      ".join(r.stdout.strip().splitlines()[-15:])
            problems.append(f"unit tests failed:\n      {tail}")
    else:
        print("[SKIP] unit tests (pytest is not installed: pip install -r requirements-dev.txt)")

    if problems:
        print(f"\n{len(problems)} problem(s) to fix:")
        for i, p in enumerate(problems, 1):
            print(f"  {i}. {p}")
        print("\nRESULT: FAIL. Fix every problem above, then run python -m tools.check again.")
        return 1
    if report.warnings:
        print(f"\n({len(report.warnings)} warnings about drafts and unchecked sources: expected until a human reviews them.)")
    print("\nRESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
