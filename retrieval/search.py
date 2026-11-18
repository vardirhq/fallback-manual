"""Layer 0 search: SQLite FTS5 + synonym groups + typo correction + a confidence check.

The algorithm is kept simple and deterministic so it can be ported to Kotlin unchanged:

1. Normalise the question and split it into words. Drop stop words.
2. Correct obvious typos against the vocabulary stored in the database.
3. Turn the words into *concepts*. A phrase found in a synonym group becomes one concept
   containing the whole group; any other word is a concept on its own.
4. Ask FTS5 for articles matching any concept, ranked by BM25 with heavy weights on the
   title, situations and keywords.
5. For each candidate, compute *coverage*: the share of the question's concepts it
   contains (full credit in title/situations/keywords, half credit in the body only).
6. The answer is *confident* only if the best article's coverage reaches the threshold
   (0.6), or a lower one (0.4) when a synonym-group concept hit its title, situations or
   keywords. Otherwise the app must say that no reviewed article matched.

Usage:
    python -m retrieval.search "someone is shivering and talking nonsense"
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DB = ROOT / "build" / "knowledge.db"

DEFAULT_THRESHOLD = 0.6
# A curated synonym concept hitting the title/situations/keywords is strong evidence on
# its own, so such matches are accepted at this lower coverage.
SYNONYM_THRESHOLD = 0.4
CANDIDATES = 10
STRONG_COLUMNS = "{title situations keywords}"
BM25_WEIGHTS = (0.0, 10.0, 6.0, 4.0, 1.0)  # id, title, situations, keywords, body

WORD_RE = re.compile(r"[^\W_]+", re.UNICODE)

STOP_WORDS = frozenset("""
a about after again all am an and any are as at be been being but by can cannot could
did do does doing don't done for from get getting got had has have having he her here hers
him his how i i'm if in into is it it's its just keep let like me might more most much
must my myself need needs no not now of on once only or other our out over own please
really right should so some someone somebody something still such than that the their them
then there these they thing things this those through to too under until up us very was
way we were what when where which while who why will with without would you your yours
anyone everyone person people guy man woman friend tell know want try trying use using
best good safe safely way ways quick quickly fast possible help
bad badly heavily lot lots terribly ok okay urgent urgently emergency hey hi
""".split())


@dataclass
class Concept:
    words: str                # what the user typed
    terms: list[str]          # what we search for
    from_synonyms: bool = False


@dataclass
class Match:
    id: str
    title: str
    category: str
    high_risk: bool
    coverage: float
    bm25: float
    strong_synonym_hit: bool = False


@dataclass
class SearchResult:
    query: str
    concepts: list[Concept]
    matches: list[Match] = field(default_factory=list)
    confident: bool = False
    corrections: dict[str, str] = field(default_factory=dict)

    @property
    def best(self) -> Match | None:
        return self.matches[0] if self.confident and self.matches else None


def normalise(text: str) -> list[str]:
    text = unicodedata.normalize("NFKC", text).lower().replace("’", "'")
    text = re.sub(r"'(s|t|re|ve|ll|d|m)\b", "", text)
    return WORD_RE.findall(text)


def _edit_distance(a: str, b: str, limit: int) -> int:
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        if min(cur) > limit:
            return limit + 1
        prev = cur
    return prev[-1]


class Searcher:
    def __init__(self, db_path: Path | str = DEFAULT_DB, threshold: float = DEFAULT_THRESHOLD):
        self.con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        self.threshold = threshold
        self.vocab = {w for (w,) in self.con.execute("SELECT word FROM vocab")}
        groups: dict[int, list[str]] = {}
        for gid, term in self.con.execute("SELECT group_id, term FROM synonyms ORDER BY rowid"):
            groups.setdefault(gid, []).append(term)
        self.groups = groups
        # phrase (as a tuple of words) -> group ids containing it
        self.phrase_groups: dict[tuple[str, ...], list[int]] = {}
        for gid, terms in groups.items():
            for t in terms:
                self.phrase_groups.setdefault(tuple(normalise(t)), []).append(gid)
        self.max_phrase = max((len(p) for p in self.phrase_groups), default=1)

    # -- query understanding -------------------------------------------------

    def _correct(self, word: str) -> str | None:
        if word in self.vocab or word in STOP_WORDS or len(word) < 5 or word.isdigit():
            return None
        limit = 1 if len(word) < 8 else 2
        best, best_d = None, limit + 1
        for v in self.vocab | STOP_WORDS:
            if v[0] != word[0]:
                continue
            d = _edit_distance(word, v, limit)
            if d < best_d or (d == best_d and best is not None and v < best):
                best, best_d = v, d
        return best if best_d <= limit else None

    def concepts(self, query: str) -> tuple[list[Concept], dict[str, str]]:
        words = normalise(query)
        corrections: dict[str, str] = {}
        fixed = []
        for w in words:
            c = self._correct(w)
            if c:
                corrections[w] = c
            fixed.append(c or w)

        out: list[Concept] = []
        i = 0
        while i < len(fixed):
            for n in range(min(self.max_phrase, len(fixed) - i), 0, -1):
                phrase = tuple(fixed[i:i + n])
                gids = self.phrase_groups.get(phrase)
                if gids is None and n == 1 and phrase[0].endswith("s"):
                    gids = self.phrase_groups.get((phrase[0][:-1],))
                if gids:
                    terms = sorted({t for g in gids for t in self.groups[g]})
                    out.append(Concept(" ".join(phrase), terms, True))
                    i += n
                    break
            else:
                w = fixed[i]
                if w not in STOP_WORDS and not w.isdigit() and len(w) > 1:
                    out.append(Concept(w, [w]))
                i += 1
        # merge concepts that came from the same synonym group ("bleeding blood")
        merged: list[Concept] = []
        for c in out:
            if any(m.terms == c.terms for m in merged):
                continue
            merged.append(c)
        return merged, corrections

    @staticmethod
    def _fts_expr(terms: list[str]) -> str:
        parts = []
        for t in terms:
            words = normalise(t)
            if words:
                parts.append('"' + " ".join(words) + '"')
        return "(" + " OR ".join(parts) + ")"

    # -- search --------------------------------------------------------------

    def search(self, query: str, limit: int = 3) -> SearchResult:
        concepts, corrections = self.concepts(query)
        result = SearchResult(query, concepts, corrections=corrections)
        if not concepts:
            return result

        exprs = [self._fts_expr(c.terms) for c in concepts]
        any_expr = " OR ".join(exprs)
        w = ", ".join(str(x) for x in BM25_WEIGHTS)
        rows = self.con.execute(
            f"SELECT f.rowid, a.id, a.title, a.category, a.high_risk, bm25(articles_fts, {w}) AS s "
            "FROM articles_fts f JOIN articles a ON a.rowid = f.rowid "
            "WHERE articles_fts MATCH ? ORDER BY s LIMIT ?",
            (any_expr, CANDIDATES),
        ).fetchall()

        matches = []
        for rowid, aid, title, cat, high_risk, score in rows:
            total, syn_hit = 0.0, False
            for c, e in zip(concepts, exprs):
                if self._hit(rowid, f"{STRONG_COLUMNS} : {e}"):
                    total += 1.0
                    syn_hit = syn_hit or c.from_synonyms
                elif self._hit(rowid, f"body : {e}"):
                    total += 0.5
            matches.append(Match(aid, title, cat, bool(high_risk), total / len(exprs), score, syn_hit))

        matches.sort(key=lambda m: (-m.coverage, m.bm25))
        result.matches = matches[:limit]
        if matches:
            top = matches[0]
            result.confident = top.coverage >= self.threshold or (
                top.strong_synonym_hit and top.coverage >= SYNONYM_THRESHOLD)
        return result

    def _hit(self, rowid: int, expr: str) -> bool:
        return self.con.execute(
            "SELECT 1 FROM articles_fts WHERE articles_fts MATCH ? AND rowid = ?", (expr, rowid)
        ).fetchone() is not None

    def close(self) -> None:
        self.con.close()


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Search the knowledge base.")
    ap.add_argument("query")
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args(argv)

    if not args.db.exists():
        print(f"{args.db} not found; run `python -m tools.build_kb` first.", file=sys.stderr)
        return 1
    s = Searcher(args.db, args.threshold)
    r = s.search(args.query)
    if args.json:
        print(json.dumps({
            "query": r.query,
            "confident": r.confident,
            "corrections": r.corrections,
            "concepts": [c.words for c in r.concepts],
            "matches": [m.__dict__ for m in r.matches],
        }, indent=2))
        return 0
    if r.corrections:
        print("Corrected: " + ", ".join(f"{a} → {b}" for a, b in r.corrections.items()))
    print("Concepts:  " + (", ".join(c.words for c in r.concepts) or "(none)"))
    if not r.confident:
        print("\nNo reviewed article matches this question well enough.")
        if r.matches:
            print("Closest articles:")
    else:
        print()
    for m in r.matches:
        flag = " [high risk]" if m.high_risk else ""
        print(f"  {m.coverage:4.0%}  {m.title} ({m.id}){flag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
