"""Validate knowledge articles and compile them into the SQLite database the app ships.

Usage:
    python -m tools.build_kb                 # development build: drafts allowed, with warnings
    python -m tools.build_kb --release       # release build: every article must be reviewed
    python -m tools.build_kb --check         # validate only, write nothing
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import sqlite3
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
ARTICLES_DIR = ROOT / "knowledge" / "articles"
SYNONYMS_FILE = ROOT / "knowledge" / "synonyms.yaml"
STOPWORDS_FILE = ROOT / "knowledge" / "stopwords.txt"
CATEGORIES_FILE = ROOT / "knowledge" / "categories.yaml"
DEFAULT_OUT = ROOT / "build" / "knowledge.db"

SCHEMA_VERSION = 1
REQUIRED_FIELDS = ["id", "title", "category", "status", "version", "high_risk", "license",
                   "situations", "keywords", "sources", "review"]
REQUIRED_SECTIONS = ["When to use", "What you need", "Steps", "Warnings", "Common mistakes", "Get help"]
STATUSES = {"draft", "reviewed"}
ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
WORD_RE = re.compile(r"[^\W_]+(?:['-][^\W_]+)*", re.UNICODE)
# Links between articles: [link text](article:target-id). No other link targets are allowed:
# the app is offline, and every link must lead to reviewed content inside it.
ARTICLE_LINK_RE = re.compile(r"\[([^\]]+)\]\(article:([^)\s]*)\)")
ANY_LINK_RE = re.compile(r"\[([^\]]+)\]\(([^)]*)\)")


@dataclass
class Article:
    path: Path
    meta: dict
    body: str
    sections: dict[str, str] = field(default_factory=dict)

    @property
    def id(self) -> str:
        return self.meta["id"]


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def error(self, where: Path | str, msg: str) -> None:
        self.errors.append(f"{_rel(where)}: {msg}")

    def warn(self, where: Path | str, msg: str) -> None:
        self.warnings.append(f"{_rel(where)}: {msg}")


def _rel(p: Path | str) -> str:
    try:
        return str(Path(p).resolve().relative_to(ROOT))
    except ValueError:
        return str(p)


def parse_article(path: Path) -> tuple[dict, str]:
    text = path.read_text(encoding="utf-8")
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, re.DOTALL)
    if not m:
        raise ValueError("missing YAML front matter (file must start with ---)")
    meta = yaml.safe_load(m.group(1)) or {}
    if not isinstance(meta, dict):
        raise ValueError("front matter must be a mapping")
    return meta, m.group(2).strip()


def split_sections(body: str) -> dict[str, str]:
    sections: dict[str, str] = {}
    current = None
    lines: list[str] = []
    for line in body.splitlines():
        if line.startswith("## "):
            if current is not None:
                sections[current] = "\n".join(lines).strip()
            current, lines = line[3:].strip(), []
        elif current is not None:
            lines.append(line)
    if current is not None:
        sections[current] = "\n".join(lines).strip()
    return sections


def _is_date(value) -> bool:
    if isinstance(value, dt.date):
        return True
    try:
        dt.date.fromisoformat(str(value))
        return True
    except ValueError:
        return False


def validate(article: Article, report: Report, release: bool) -> None:
    p, meta = article.path, article.meta

    for f in REQUIRED_FIELDS:
        if f not in meta:
            report.error(p, f"missing field '{f}'")
    if report.errors and any(e.startswith(_rel(p)) for e in report.errors):
        return

    if not ID_RE.match(str(meta["id"])):
        report.error(p, f"id '{meta['id']}' must be lowercase words joined by hyphens")
    if meta["id"] != p.stem:
        report.error(p, f"id '{meta['id']}' does not match file name '{p.stem}'")
    if meta["category"] != p.parent.name:
        report.error(p, f"category '{meta['category']}' does not match folder '{p.parent.name}'")
    if meta["status"] not in STATUSES:
        report.error(p, f"status must be one of {sorted(STATUSES)}")
    if not isinstance(meta["version"], int) or meta["version"] < 1:
        report.error(p, "version must be a positive integer")
    if not isinstance(meta["high_risk"], bool):
        report.error(p, "high_risk must be true or false")
    if not meta["license"]:
        report.error(p, "license is empty")
    for f in ("situations", "keywords"):
        if not isinstance(meta[f], list) or not meta[f]:
            report.error(p, f"{f} must be a non-empty list")

    sources = meta["sources"]
    if not isinstance(sources, list) or not sources:
        report.error(p, "at least one source is required")
    else:
        for i, s in enumerate(sources):
            if not isinstance(s, dict) or not s.get("title") or not s.get("publisher"):
                report.error(p, f"source {i + 1} needs a title and a publisher")
            elif not s.get("checked"):
                report.warn(p, f"source '{s['title']}' has not been checked against the original")

    for name in REQUIRED_SECTIONS:
        if name not in article.sections:
            report.error(p, f"missing section '## {name}'")
        elif not article.sections[name]:
            report.error(p, f"section '## {name}' is empty")
    steps = article.sections.get("Steps", "")
    if steps and not re.search(r"^\s*1\.\s", steps, re.MULTILINE):
        report.error(p, "'## Steps' must be a numbered list")

    review = meta["review"] or {}
    if meta["status"] == "reviewed":
        if not review.get("reviewer"):
            report.error(p, "reviewed article must name a reviewer")
        for f in ("date", "review_by"):
            if not _is_date(review.get(f)):
                report.error(p, f"reviewed article needs review.{f} as YYYY-MM-DD")
        if _is_date(review.get("review_by")) and str(review["review_by"]) < dt.date.today().isoformat():
            report.warn(p, f"review is overdue (review_by {review['review_by']})")
    elif release:
        report.error(p, "draft article cannot be included in a release build")
    else:
        report.warn(p, "draft: not reviewed")


def load_categories(path: Path, report: Report) -> list[dict]:
    cats = (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("categories", [])
    for i, c in enumerate(cats):
        if not isinstance(c, dict) or not c.get("id") or not c.get("title"):
            report.error(path, f"category {i + 1} needs an id and a title")
    return cats


def load_stopwords(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return sorted({l.strip().lower() for l in lines if l.strip() and not l.startswith("#")})


def load_articles(articles_dir: Path, report: Report, release: bool) -> list[Article]:
    articles: list[Article] = []
    seen: dict[str, Path] = {}
    for path in sorted(articles_dir.glob("*/*.md")):
        try:
            meta, body = parse_article(path)
        except (ValueError, yaml.YAMLError) as e:
            report.error(path, str(e))
            continue
        art = Article(path, meta, body, split_sections(body))
        validate(art, report, release)
        aid = meta.get("id")
        if aid in seen:
            report.error(path, f"duplicate id '{aid}' (also in {_rel(seen[aid])})")
        elif aid:
            seen[aid] = path
        articles.append(art)
    if not articles:
        report.error(articles_dir, "no articles found")
    check_links(articles, report)
    return articles


def check_links(articles: list[Article], report: Report) -> None:
    ids = {a.meta.get("id") for a in articles}
    for a in articles:
        for m in ANY_LINK_RE.finditer(a.body):
            target = m.group(2)
            if not target.startswith("article:"):
                report.error(a.path, f"link '{m.group(0)}' must point to another article as (article:<id>)")
            elif target[len("article:"):] not in ids:
                report.error(a.path, f"link '{m.group(0)}' points to an article that does not exist")
            elif target[len("article:"):] == a.meta.get("id"):
                report.error(a.path, f"link '{m.group(0)}' points to the article itself")


def load_synonyms(path: Path, report: Report) -> list[list[str]]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    groups = data.get("groups", [])
    out = []
    for i, g in enumerate(groups):
        if not isinstance(g, list) or len(g) < 2:
            report.error(path, f"group {i + 1} must list at least two terms")
            continue
        out.append([str(t).lower().strip() for t in g])
    return out


def _plain(markdown: str) -> str:
    text = ARTICLE_LINK_RE.sub(r"\1", markdown)  # index the link text, not the target id
    return re.sub(r"[*_`#>\[\]()]", " ", text)


def _opt_str(v) -> str | None:
    return None if v is None else str(v)


def _json_default(o):
    if isinstance(o, (dt.date, dt.datetime)):
        return o.isoformat()
    raise TypeError(type(o))


def write_db(articles: list[Article], synonyms: list[list[str]], out: Path, release: bool,
             categories: list[dict] = (), stopwords: list[str] = ()) -> str:
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.executescript(
        """
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE articles (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            category TEXT NOT NULL,
            status TEXT NOT NULL,
            version INTEGER NOT NULL,
            high_risk INTEGER NOT NULL,
            license TEXT NOT NULL,
            body_md TEXT NOT NULL,
            situations TEXT NOT NULL,
            keywords TEXT NOT NULL,
            reviewer TEXT,
            review_date TEXT,
            review_by TEXT,
            diagrams_needed TEXT NOT NULL
        );
        CREATE TABLE sources (
            article_id TEXT NOT NULL REFERENCES articles(id),
            position INTEGER NOT NULL,
            title TEXT NOT NULL,
            publisher TEXT NOT NULL,
            url TEXT,
            checked INTEGER NOT NULL,
            PRIMARY KEY (article_id, position)
        );
        CREATE VIRTUAL TABLE articles_fts USING fts5(
            id UNINDEXED, title, situations, keywords, body,
            tokenize = 'porter unicode61 remove_diacritics 2'
        );
        CREATE TABLE synonyms (group_id INTEGER NOT NULL, term TEXT NOT NULL);
        CREATE INDEX synonyms_term ON synonyms(term);
        CREATE TABLE vocab (word TEXT PRIMARY KEY) WITHOUT ROWID;
        CREATE TABLE stopwords (word TEXT PRIMARY KEY) WITHOUT ROWID;
        CREATE TABLE categories (id TEXT PRIMARY KEY, title TEXT NOT NULL, position INTEGER NOT NULL);
        """
    )
    digest = hashlib.sha256()
    vocab: set[str] = set()
    for a in sorted(articles, key=lambda a: a.id):
        m = a.meta
        review = m["review"] or {}
        row = (
            m["id"], m["title"], m["category"], m["status"], m["version"], int(m["high_risk"]),
            m["license"], a.body,
            json.dumps(m["situations"]), json.dumps(m["keywords"]),
            _opt_str(review.get("reviewer")), _opt_str(review.get("date")), _opt_str(review.get("review_by")),
            json.dumps(m.get("diagrams_needed") or []),
        )
        con.execute("INSERT INTO articles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
        for i, src in enumerate(m["sources"]):
            con.execute("INSERT INTO sources VALUES (?,?,?,?,?,?)",
                        (m["id"], i, src["title"], src["publisher"], _opt_str(src.get("url")),
                         int(bool(src.get("checked")))))
        digest.update(json.dumps(m["sources"], sort_keys=True, default=_json_default).encode())
        situations, keywords = " . ".join(m["situations"]), " . ".join(m["keywords"])
        body = _plain(a.body)
        con.execute(
            "INSERT INTO articles_fts (rowid, id, title, situations, keywords, body) "
            "VALUES ((SELECT rowid FROM articles WHERE id = ?), ?, ?, ?, ?, ?)",
            (m["id"], m["id"], m["title"], situations, keywords, body),
        )
        for text in (m["title"], situations, keywords, body):
            vocab.update(w.lower() for w in WORD_RE.findall(text))
        digest.update(json.dumps(row, sort_keys=True).encode())

    for gid, group in enumerate(synonyms):
        for term in group:
            con.execute("INSERT INTO synonyms VALUES (?, ?)", (gid, term))
            vocab.update(WORD_RE.findall(term))
        digest.update(json.dumps(group).encode())
    con.executemany("INSERT OR IGNORE INTO vocab VALUES (?)", ((w,) for w in sorted(vocab)))
    con.executemany("INSERT INTO stopwords VALUES (?)", ((w,) for w in stopwords))
    con.executemany("INSERT INTO categories VALUES (?, ?, ?)",
                    ((c["id"], c["title"], i) for i, c in enumerate(categories)))
    digest.update(json.dumps([list(stopwords), list(categories)]).encode())

    content_hash = digest.hexdigest()[:16]
    meta = {
        "schema_version": str(SCHEMA_VERSION),
        "content_hash": content_hash,
        "release": "1" if release else "0",
        "article_count": str(len(articles)),
    }
    con.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
    con.commit()
    con.execute("VACUUM")
    con.close()
    tmp.replace(out)
    return content_hash


def build(out: Path = DEFAULT_OUT, release: bool = False, check_only: bool = False,
          articles_dir: Path = ARTICLES_DIR, synonyms_file: Path = SYNONYMS_FILE,
          categories_file: Path = CATEGORIES_FILE, stopwords_file: Path = STOPWORDS_FILE) -> Report:
    report = Report()
    articles = load_articles(articles_dir, report, release)
    synonyms = load_synonyms(synonyms_file, report)
    categories = load_categories(categories_file, report)
    known = {c.get("id") for c in categories if isinstance(c, dict)}
    for a in articles:
        if a.meta.get("category") and a.meta["category"] not in known:
            report.error(a.path, f"category '{a.meta['category']}' is not listed in {_rel(categories_file)}")
    stopwords = load_stopwords(stopwords_file)
    if not report.errors and not check_only:
        write_db(articles, synonyms, out, release, categories, stopwords)
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--release", action="store_true", help="require every article to be reviewed")
    ap.add_argument("--check", action="store_true", help="validate only")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--quiet", action="store_true", help="hide warnings")
    args = ap.parse_args(argv)

    report = build(args.out, args.release, args.check)
    if not args.quiet:
        for w in report.warnings:
            print(f"warning: {w}", file=sys.stderr)
    for e in report.errors:
        print(f"error: {e}", file=sys.stderr)
    if report.errors:
        print(f"\n{len(report.errors)} error(s); nothing written.", file=sys.stderr)
        return 1
    if args.check:
        print(f"OK: all articles valid ({len(report.warnings)} warning(s)).")
    else:
        size = args.out.stat().st_size / 1024
        print(f"Wrote {_rel(args.out)} ({size:.0f} KB, {len(report.warnings)} warning(s)).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
