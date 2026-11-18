"""Validate knowledge articles and compile them into the SQLite database the app ships.

Usage:
    python -m tools.build_kb                 # development build: drafts allowed, with warnings
    python -m tools.build_kb --release       # release build: every article must be reviewed
    python -m tools.build_kb --check         # validate only, write nothing
"""

from __future__ import annotations

import argparse
import datetime as dt
import difflib
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
REVIEWERS_FILE = ROOT / "knowledge" / "reviewers.yaml"


def approved_reviewers() -> set[str]:
    if not REVIEWERS_FILE.exists():
        return set()
    data = yaml.safe_load(REVIEWERS_FILE.read_text(encoding="utf-8")) or {}
    return {str(r["name"]).strip() for r in data.get("reviewers") or [] if isinstance(r, dict) and r.get("name")}
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


ALLOWED_FIELDS = REQUIRED_FIELDS + ["diagrams_needed"]
SOURCE_FIELDS = {"title", "publisher", "url", "checked"}
REVIEW_FIELDS = {"reviewer", "date", "review_by"}
LICENSES = {"CC-BY-4.0"}
LIST_SECTIONS = {"What you need", "Warnings", "Common mistakes"}
STEP_WORDS_WARN = 20   # aim for this or fewer
STEP_WORDS_MAX = 25    # longer steps are rejected: one action per step

# Articles use British spelling. American -> British. Only titles and article text are checked;
# keywords and situations may use any spelling, because people type both.
US_TO_UK = {
    "color": "colour", "colors": "colours", "colored": "coloured", "colorless": "colourless",
    "center": "centre", "centers": "centres", "centered": "centred",
    "liter": "litre", "liters": "litres", "milliliter": "millilitre", "milliliters": "millilitres",
    "diarrhea": "diarrhoea", "hemorrhage": "haemorrhage", "anemia": "anaemia", "edema": "oedema",
    "pediatric": "paediatric", "esophagus": "oesophagus", "anesthetic": "anaesthetic", "fetus": "foetus",
    "recognize": "recognise", "recognized": "recognised", "recognizes": "recognises", "recognizing": "recognising",
    "realize": "realise", "realized": "realised", "organize": "organise", "organized": "organised",
    "minimize": "minimise", "maximize": "maximise", "sterilize": "sterilise", "sterilized": "sterilised",
    "immobilize": "immobilise", "immobilized": "immobilised", "stabilize": "stabilise",
    "gray": "grey", "fiber": "fibre", "behavior": "behaviour", "favor": "favour", "odor": "odour",
    "signaling": "signalling", "signaled": "signalled", "traveling": "travelling", "traveled": "travelled",
    "labeled": "labelled", "canceled": "cancelled", "fueled": "fuelled", "aluminum": "aluminium",
    "mold": "mould", "moldy": "mouldy", "jewelry": "jewellery", "pajamas": "pyjamas", "defense": "defence",
}
US_RE = re.compile(r"\b(" + "|".join(sorted(US_TO_UK, key=len, reverse=True)) + r")\b", re.I)

# Titles name the problem ("Dehydration", "Burns and scalds"), not the activity of reading about it.
BAD_TITLE_RE = re.compile(r"^(how to|how do|guide|a guide|understanding|recogni[sz]ing|identifying|responding|"
                          r"dealing with|managing|introduction|an introduction|what to do|everything|tips)\b", re.I)

# Things language models leave behind when copying research output, with how to fix each.
# None of them belong in an article.
AI_ARTIFACTS = [
    (re.compile(r"\ue200|\ue201|\ue202|cite(turn|\u2216)|turn\d+(search|view|news|fetch)\d+"),
     "a ChatGPT citation marker", "delete it. Sources belong only in the 'sources' list in the header"),
    (re.compile(r"[\u3010\u3011]"), "a citation bracket like \u3010 \u3011",
     "delete it. Sources belong only in the 'sources' list in the header"),
    (re.compile(r"oaicite|contentReference"), "a ChatGPT citation marker",
     "delete it. Sources belong only in the 'sources' list in the header"),
    (re.compile(r"utm_source="), "a tracking parameter (utm_source=...)",
     "remove '?utm_source=...' (and anything after it) from the address"),
    (re.compile(r"\[\^?\d+\]"), "a numbered citation like [1] or [^1]",
     "delete it. Sources belong only in the 'sources' list in the header"),
    (re.compile(r"\bas an ai\b|\blanguage model\b|\bI cannot\b|\bI can't\b", re.I), "chatbot wording",
     "rewrite the sentence as a plain instruction"),
    (re.compile(r"\bTODO\b|\bTBD\b|\bFIXME\b|lorem ipsum|\bXXX\b", re.I), "unfinished template text (TODO)",
     "replace the whole TODO text with real content"),
]


def check_file_text(p: Path, report: Report) -> None:
    """Scans the whole file, so every problem can be reported with its line number."""
    lines = p.read_text(encoding="utf-8").splitlines()
    for rx, what, fix in AI_ARTIFACTS:
        hits = [n for n, line in enumerate(lines, 1) if rx.search(line)]
        if hits:
            shown = ", ".join(map(str, hits[:15])) + (" ..." if len(hits) > 15 else "")
            report.error(p, f"{what} on line(s) {shown}. Fix: {fix}.")


def _is_str_list(v, min_len: int) -> bool:
    return isinstance(v, list) and len(v) >= min_len and all(isinstance(x, str) and x.strip() for x in v)


def validate(article: Article, report: Report, release: bool) -> None:
    """Checks one article. Every message says what is wrong and how to fix it."""
    p, meta = article.path, article.meta
    # --- header fields ------------------------------------------------------
    # Every check runs even when others fail, so all problems are reported in one pass.
    if p.name != p.name.lower() or not p.name.endswith(".md"):
        report.error(p, "file name must be lowercase and end in .md. Fix: rename the file, e.g. water-filtration.md.")
    for f in meta:
        if f not in ALLOWED_FIELDS:
            close = difflib.get_close_matches(str(f), ALLOWED_FIELDS, n=1)
            hint = f"Did you mean '{close[0]}'? " if close else ""
            report.error(p, f"unknown header field '{f}'. {hint}Fix: rename or remove it. "
                            f"Allowed fields: {', '.join(ALLOWED_FIELDS)}.")
    for f in REQUIRED_FIELDS:
        if f not in meta and not difflib.get_close_matches(f, [str(k) for k in meta if k not in ALLOWED_FIELDS], n=1):
            report.error(p, f"missing header field '{f}'. Fix: copy it from knowledge/article-template.md.")
    has = meta.__contains__

    if has("id"):
        if not ID_RE.match(str(meta["id"])):
            report.error(p, f"id '{meta['id']}' is invalid. Fix: use lowercase words joined by hyphens, e.g. water-filtration.")
        elif meta["id"] != p.stem:
            report.error(p, f"id '{meta['id']}' does not match the file name '{p.name}'. Fix: make them identical "
                            f"(id: {p.stem}).")
    if not ID_RE.match(p.stem):
        report.error(p, f"file name '{p.name}' is invalid. Fix: use lowercase words joined by hyphens, e.g. water-filtration.md.")
    if has("title"):
        title = meta["title"]
        if not isinstance(title, str) or not title.strip():
            report.error(p, "title is empty. Fix: give a short title, e.g. 'Filtering water'.")
        elif len(title) > 60 or title.rstrip().endswith("."):
            report.error(p, "title must be at most 60 characters and must not end with a full stop.")
        elif BAD_TITLE_RE.search(title):
            report.error(p, f"title '{title}' describes reading about the problem. Fix: name the problem itself, "
                            "like the other articles do, e.g. 'Dehydration', 'Burns and scalds', 'Hypothermia'.")
    if has("category") and meta["category"] != p.parent.name:
        report.error(p, f"category '{meta['category']}' does not match the folder '{p.parent.name}'. "
                        f"Fix: set category: {p.parent.name}, or move the file to the right folder.")
    if has("status") and meta["status"] not in STATUSES:
        report.error(p, "status must be 'draft' or 'reviewed'. Fix: new articles always use status: draft.")
    if has("version") and (isinstance(meta["version"], bool) or not isinstance(meta["version"], int) or meta["version"] < 1):
        report.error(p, "version must be a whole number of 1 or more, without quotes. Fix: version: 1 for a new article.")
    if has("high_risk") and not isinstance(meta["high_risk"], bool):
        report.error(p, "high_risk must be true or false (without quotes).")
    if has("license") and meta["license"] not in LICENSES:
        report.error(p, f"license must be one of {sorted(LICENSES)}. Fix: license: CC-BY-4.0")
    if has("situations") and not _is_str_list(meta["situations"], 2):
        report.error(p, "situations must list at least 2 sentences describing the problem in everyday words.")
    if has("keywords"):
        if not _is_str_list(meta["keywords"], 3):
            report.error(p, "keywords must list at least 3 words or short phrases, e.g. [water, filter, cloth].")
        elif any(k != k.lower() for k in meta["keywords"]) and not any("TODO" in k for k in meta["keywords"]):
            report.error(p, "keywords must be lowercase. Fix: write e.g. 'cpr', not 'CPR'.")
    if "diagrams_needed" in meta and meta["diagrams_needed"] not in (None, []) and not _is_str_list(meta["diagrams_needed"], 1):
        report.error(p, "diagrams_needed must be a list of short descriptions, or be left out.")

    # --- sources ------------------------------------------------------------
    sources = meta.get("sources")
    if has("sources") and (not isinstance(sources, list) or not sources):
        report.error(p, "at least one source is required. Fix: add the official page(s) you used under 'sources'.")
    if not isinstance(sources, list):
        sources = []
    for i, s in enumerate(sources, 1):
        if not isinstance(s, dict):
            report.error(p, f"source {i} must have title, publisher, url and checked on separate lines.")
            continue
        for k in s:
            if k not in SOURCE_FIELDS:
                report.error(p, f"source {i} has unknown field '{k}'. Allowed: title, publisher, url, checked.")
        if not isinstance(s.get("title"), str) or not s["title"].strip():
            report.error(p, f"source {i} needs a title (the page or document title).")
        if not isinstance(s.get("publisher"), str) or not s["publisher"].strip():
            report.error(p, f"source {i} needs a publisher (the organisation, e.g. World Health Organization).")
        url = s.get("url")
        if url is None:
            report.warn(p, f"source {i} has no url; reviewers need one to check it.")
        elif not isinstance(url, str) or not url.startswith("https://") or " " in url:
            if isinstance(url, str) and "TODO" in url:
                continue
            report.error(p, f"source {i} url must be a full https:// address, or null.")
        if not isinstance(s.get("checked"), bool):
            report.error(p, f"source {i} needs checked: false (only a human reviewer sets it to true).")
        elif s["checked"] and meta.get("status") != "reviewed":
            report.error(p, f"source {i} has checked: true in a draft. Fix: set checked: false. Only a human "
                            "reviewer marks sources as checked, when they review the article.")
        elif not s["checked"]:
            report.warn(p, f"source '{s.get('title')}' has not been checked against the original")
    if meta.get("high_risk") is True and len({s.get("publisher") for s in sources if isinstance(s, dict)}) < 2:
        report.error(p, "high_risk articles need sources from at least 2 different publishers. "
                        "Fix: add a second official source that says the same thing.")

    # --- review ---------------------------------------------------------------
    review = meta.get("review") if isinstance(meta.get("review"), dict) else None
    if has("review") and (review is None or any(k not in REVIEW_FIELDS for k in review)):
        report.error(p, "review must contain exactly reviewer, date and review_by. Fix: copy the block from the template.")
        review = {}
    if meta.get("status") == "reviewed":
        if not review.get("reviewer"):
            report.error(p, "a reviewed article must name its reviewer.")
        elif str(review["reviewer"]).strip() not in approved_reviewers():
            report.error(p, f"reviewer '{review['reviewer']}' is not in knowledge/reviewers.yaml. Fix: if you are an AI "
                            "or did not get a human expert's approval, set status: draft and set reviewer, date and "
                            "review_by to null. Only the project owner adds reviewers.")
        for f in ("date", "review_by"):
            if not _is_date(review.get(f)):
                report.error(p, f"a reviewed article needs review.{f} as YYYY-MM-DD.")
        if _is_date(review.get("review_by")) and str(review["review_by"]) < dt.date.today().isoformat():
            report.warn(p, f"review is overdue (review_by {review['review_by']})")
    else:
        if any(review.get(f) is not None for f in REVIEW_FIELDS):
            report.error(p, "a draft must have reviewer, date and review_by set to null. "
                            "Only a human reviewer fills these in, together with status: reviewed.")
        if release:
            report.error(p, "draft article cannot be included in a release build")
        else:
            report.warn(p, "draft: not reviewed")

    # --- body -----------------------------------------------------------------
    validate_body(article, report)


def _check_spelling(p: Path, report: Report, where: str, text: str) -> None:
    for m in US_RE.finditer(text):
        word = m.group(1)
        uk = US_TO_UK[word.lower()]
        uk = uk.capitalize() if word[0].isupper() else uk
        report.error(p, f"{where}: '{word}' is American spelling. Fix: write '{uk}' (articles use British spelling).")


def validate_body(article: Article, report: Report) -> None:
    p, body = article.path, article.body
    file_lines = p.read_text(encoding="utf-8").splitlines()
    # The body starts after the second '---' line; report positions as real file line numbers.
    dashes = [i for i, l in enumerate(file_lines) if l.strip() == "---"]
    first_body = dashes[1] + 1 if len(dashes) > 1 else 0
    headings = []
    for n in range(first_body, len(file_lines)):
        line = file_lines[n]
        where = f"line {n + 1}"
        stripped = line.strip()
        if line.startswith("#"):
            if line.startswith("## ") and not line.startswith("### "):
                headings.append(line[3:].strip())
            else:
                report.error(p, f"{where}: only '## ' headings are allowed ('{stripped[:40]}'). "
                                "Fix: the title goes in the header; use only the six ## section headings.")
        elif stripped.startswith("```"):
            report.error(p, f"{where}: code blocks are not supported. Fix: write plain text.")
        elif stripped.startswith("|"):
            report.error(p, f"{where}: tables are not supported. Fix: use a bullet list instead.")
        elif "![" in line:
            report.error(p, f"{where}: images are not supported yet. Fix: remove it and describe the picture "
                            "under diagrams_needed in the header.")
        elif re.match(r"^\s+([-*+]|\d+\.)\s", line):
            report.error(p, f"{where}: nested or indented list items are not supported. "
                            "Fix: start every list item at the beginning of the line.")
        elif re.match(r"^([*+\u2022])\s", stripped):
            report.error(p, f"{where}: bullet items must start with '- '. Fix: replace '{stripped[0]}' with '-'.")
        elif re.search(r"<[a-zA-Z/][^>]*>", line):
            report.error(p, f"{where}: HTML is not supported. Fix: write plain text.")
        _check_spelling(p, report, where, ARTICLE_LINK_RE.sub(r"\1", line))
    title_line = next((i for i, l in enumerate(file_lines) if l.startswith("title:")), None)
    if title_line is not None:
        _check_spelling(p, report, f"line {title_line + 1}", file_lines[title_line])
    check_file_text(p, report)

    if headings != REQUIRED_SECTIONS:
        report.error(p, "the body must have exactly these six headings, in this order: "
                        + ", ".join(f"'## {h}'" for h in REQUIRED_SECTIONS)
                        + f". Found: {', '.join(repr(h) for h in headings) or 'none'}.")
        return
    first = body.splitlines()[0].strip() if body else ""
    if first != "## When to use":
        report.error(p, "the body must start with '## When to use' right after the header.")

    for name in REQUIRED_SECTIONS:
        text = article.sections.get(name, "")
        if not text:
            report.error(p, f"section '## {name}' is empty.")
            continue
        lines = [l for l in text.splitlines() if l.strip()]
        if name == "Steps":
            nums = []
            for l in lines:
                m = re.match(r"^(\d+)\.\s+\S", l)
                if not m:
                    report.error(p, f"'## Steps' may only contain numbered steps ('1. Do this.'). "
                                    f"Found: '{l.strip()[:50]}'. Fix: make it a step or move it to another section.")
                    break
                nums.append(int(m.group(1)))
                words = len(ARTICLE_LINK_RE.sub(r"\1", l[m.end(1) + 1:]).split())
                step = f"step {m.group(1)}"
                if words > STEP_WORDS_MAX:
                    report.error(p, f"{step} has {words} words; the maximum is {STEP_WORDS_MAX}. Fix: split it into two "
                                    "steps, one action each, and renumber the steps after it.")
                elif words > STEP_WORDS_WARN:
                    report.warn(p, f"{step} has {words} words; aim for {STEP_WORDS_WARN} or fewer.")
            else:
                if len(nums) < 2:
                    report.error(p, "'## Steps' needs at least 2 numbered steps.")
                elif nums != list(range(1, len(nums) + 1)):
                    report.error(p, f"steps must be numbered 1, 2, 3, ... without gaps. Found: {nums}.")
        elif name in LIST_SECTIONS:
            bad = [l for l in lines if not l.startswith("- ")]
            if bad:
                report.error(p, f"'## {name}' may only contain bullet items starting with '- '. "
                                f"Found: '{bad[0].strip()[:50]}'.")


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
            report.error(path, f"the header could not be read: {e}. Fix: the file must start with a line '---', "
                               "then the header, then '---'. Put any value containing ':' in double quotes.")
            continue
        art = Article(path, meta, body, split_sections(body))
        validate(art, report, release)
        aid = meta.get("id")
        if isinstance(aid, str) and aid in seen:
            report.error(path, f"duplicate id '{aid}' (also in {_rel(seen[aid])})")
        elif aid:
            seen[aid] = path
        articles.append(art)
    if not articles:
        report.error(articles_dir, "no articles found")
    check_links(articles, report)
    titles: dict[str, Path] = {}
    for a in articles:
        t = str(a.meta.get("title", "")).strip().lower()
        if t and t in titles:
            report.error(a.path, f"title '{a.meta['title']}' is already used by {_rel(titles[t])}. Fix: choose a different title.")
        titles.setdefault(t, a.path)
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
