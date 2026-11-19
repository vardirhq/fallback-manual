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
IMAGES_FILE = ROOT / "knowledge" / "images.yaml"
ICONS_FILE = ROOT / "knowledge" / "icons.yaml"
IMAGES_DIR = ROOT / "knowledge" / "images"


def approved_reviewers() -> set[str]:
    if not REVIEWERS_FILE.exists():
        return set()
    data = yaml.safe_load(REVIEWERS_FILE.read_text(encoding="utf-8")) or {}
    return {str(r["name"]).strip() for r in data.get("reviewers") or [] if isinstance(r, dict) and r.get("name")}
DEFAULT_OUT = ROOT / "build" / "knowledge.db"

SCHEMA_VERSION = 4  # 2: images table; 3: codes, icons, rhythm, lead; 4: emergency
REQUIRED_FIELDS = ["id", "title", "category", "code", "status", "version", "high_risk", "license",
                   "situations", "keywords", "sources", "review"]
REQUIRED_SECTIONS = ["When to use", "What you need", "Steps", "Warnings", "Common mistakes", "Get help"]

# Languages articles can be translated into. English is the original; a translation of
# <category>/<id>.md is <category>/<id>.<lang>.md and must use these six headings, in this order.
# Each language gets its own database: knowledge.db (English), knowledge-<lang>.db.
LANGUAGES = {
    "en": {"name": "English", "sections": REQUIRED_SECTIONS},
    "nb": {"name": "Norwegian Bokmål",
           "sections": ["Når brukes dette", "Dette trenger du", "Trinn", "Advarsler", "Vanlige feil", "Få hjelp"]},
}
# A translation's header has only these fields; the rest (category, code, high_risk, emergency,
# icon, rhythm, diagrams_needed) always come from the English article, so they can't drift apart.
TRANSLATION_REQUIRED = ["id", "lang", "translation_of", "title", "status", "version", "license",
                        "situations", "keywords", "sources", "review"]
INHERITED_FIELDS = ["category", "code", "high_risk", "emergency", "icon", "rhythm", "diagrams_needed"]
# Search: English words are stemmed ("burns" finds "burn"). SQLite has no stemmer for other
# languages, so they are matched as whole words (with typo correction and synonyms).
FTS_TOKENIZE = {"en": "porter unicode61 remove_diacritics 2"}
FTS_TOKENIZE_OTHER = "unicode61 remove_diacritics 2"
STATUSES = {"draft", "reviewed"}
ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
WORD_RE = re.compile(r"[^\W_]+(?:['-][^\W_]+)*", re.UNICODE)
# Links between articles: [link text](article:target-id). No other link targets are allowed:
# the app is offline, and every link must lead to reviewed content inside it.
ARTICLE_LINK_RE = re.compile(r"\[([^\]]+)\]\(article:([^)\s]*)\)")
ANY_LINK_RE = re.compile(r"(?<!!)\[([^\]]+)\]\(([^)]*)\)")
# A picture, on a line of its own directly under the step, bullet or paragraph it shows:
# ![alt text](image:<id>)
IMAGE_LINE_RE = re.compile(r"^!\[([^\]]+)\]\(image:([a-z0-9]+(?:-[a-z0-9]+)*)\)$")
IMAGE_ANY_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")

IMAGE_FIELDS = {"id", "file", "license", "creator", "source", "ai_generated"}
IMAGE_LICENSES = {"public-domain", "CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0"}
IMAGE_TYPES = {".webp": "image/webp", ".png": "image/png"}
IMAGE_MAX_BYTES = 150 * 1024
ALT_MIN, ALT_MAX = 10, 200


@dataclass
class Article:
    path: Path
    meta: dict
    body: str
    sections: dict[str, str] = field(default_factory=dict)
    images: list[tuple[str, int]] = field(default_factory=list)  # (image id, file line)
    step_count: int = 0
    lang: str = "en"
    translation_of: int | None = None  # for a translation: the English version it was made from

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


# emergency: true puts the article on the home screen's short list of act-now topics. A person
# decides which articles are on it; it must also be high_risk.
ALLOWED_FIELDS = REQUIRED_FIELDS + ["diagrams_needed", "icon", "rhythm", "emergency"]
CODE_RE = re.compile(r"^[A-Z]{2}-\d{2}$")
RHYTHM_FIELDS = {"bpm", "from_step", "to_step"}
SOURCE_FIELDS = {"title", "publisher", "url", "checked"}
REVIEW_FIELDS = {"reviewer", "date", "review_by"}
LICENSES = {"CC-BY-4.0"}
LIST_SECTIONS = {"What you need", "Warnings", "Common mistakes"}
STEP_WORDS_WARN = 20   # aim for this or fewer
STEP_WORDS_MAX = 25    # longer steps are rejected: one action per step

# Articles use American English. British -> American, for spelling and a few everyday words.
# Only titles and article text are checked; keywords and situations may use any spelling,
# because people type both.
UK_TO_US = {
    "colour": "color", "colours": "colors", "coloured": "colored", "colourful": "colorful", "colourless": "colorless",
    "centre": "center", "centres": "centers", "centred": "centered",
    "litre": "liter", "litres": "liters", "millilitre": "milliliter", "millilitres": "milliliters",
    "metre": "meter", "metres": "meters", "centimetre": "centimeter", "centimetres": "centimeters",
    "diarrhoea": "diarrhea", "haemorrhage": "hemorrhage", "anaemia": "anemia", "oedema": "edema",
    "paediatric": "pediatric", "oesophagus": "esophagus", "anaesthetic": "anesthetic", "foetus": "fetus",
    "recognise": "recognize", "recognised": "recognized", "recognises": "recognizes", "recognising": "recognizing",
    "realise": "realize", "realised": "realized", "organise": "organize", "organised": "organized",
    "minimise": "minimize", "maximise": "maximize", "sterilise": "sterilize", "sterilised": "sterilized",
    "immobilise": "immobilize", "immobilised": "immobilized", "stabilise": "stabilize",
    "grey": "gray", "fibre": "fiber", "behaviour": "behavior", "favour": "favor", "odour": "odor",
    "signalling": "signaling", "signalled": "signaled", "travelling": "traveling", "travelled": "traveled",
    "labelled": "labeled", "cancelled": "canceled", "fuelled": "fueled", "aluminium": "aluminum",
    "mould": "mold", "mouldy": "moldy", "jewellery": "jewelry", "pyjamas": "pajamas", "defence": "defense",
    "tyre": "tire", "tyres": "tires", "programme": "program", "whilst": "while", "amongst": "among",
    # everyday words
    "nappy": "diaper", "nappies": "diapers", "cling film": "plastic wrap", "paraffin": "kerosene",
    "cooker": "stove", "petrol": "gasoline", "fizzy drink": "soda", "fizzy drinks": "sodas",
}
UK_RE = re.compile(r"\b(" + "|".join(re.escape(k) for k in sorted(UK_TO_US, key=len, reverse=True)) + r")\b", re.I)

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
    stem = p.name[: -len(f".{article.lang}.md")] if article.lang != "en" else p.stem
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
    for f in REQUIRED_FIELDS if article.lang == "en" else []:  # translations: see load_translations
        if f not in meta and not difflib.get_close_matches(f, [str(k) for k in meta if k not in ALLOWED_FIELDS], n=1):
            report.error(p, f"missing header field '{f}'. Fix: copy it from knowledge/article-template.md.")
    has = meta.__contains__

    if has("id"):
        if not ID_RE.match(str(meta["id"])):
            report.error(p, f"id '{meta['id']}' is invalid. Fix: use lowercase words joined by hyphens, e.g. water-filtration.")
        elif meta["id"] != stem:
            report.error(p, f"id '{meta['id']}' does not match the file name '{p.name}'. Fix: make them identical "
                            f"(id: {stem}).")
    if not ID_RE.match(stem):
        report.error(p, f"file name '{p.name}' is invalid. Fix: use lowercase words joined by hyphens, e.g. water-filtration.md.")
    if has("title"):
        title = meta["title"]
        if not isinstance(title, str) or not title.strip():
            report.error(p, "title is empty. Fix: give a short title, e.g. 'Filtering water'.")
        elif len(title) > 60 or title.rstrip().endswith("."):
            report.error(p, "title must be at most 60 characters and must not end with a full stop.")
        elif article.lang == "en" and BAD_TITLE_RE.search(title):
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
    if "emergency" in meta:
        if not isinstance(meta["emergency"], bool):
            report.error(p, "emergency must be true or false (without quotes), or be left out.")
        elif meta["emergency"] and meta.get("high_risk") is not True:
            report.error(p, "emergency: true is only for high_risk articles. Fix: remove the emergency line.")
    if has("license") and meta["license"] not in LICENSES:
        report.error(p, f"license must be one of {sorted(LICENSES)}. Fix: license: CC-BY-4.0")
    if has("situations") and not _is_str_list(meta["situations"], 2):
        report.error(p, "situations must list at least 2 sentences describing the problem in everyday words.")
    if has("keywords"):
        if not _is_str_list(meta["keywords"], 3):
            report.error(p, "keywords must list at least 3 words or short phrases, e.g. [water, filter, cloth].")
        elif any(k != k.lower() for k in meta["keywords"]) and not any("TODO" in k for k in meta["keywords"]):
            report.error(p, "keywords must be lowercase. Fix: write e.g. 'cpr', not 'CPR'.")
    if has("code") and not CODE_RE.match(str(meta["code"])):
        report.error(p, f"code '{meta['code']}' is invalid. Fix: never write a code yourself; "
                        "python -m tools.new_article assigns one (two capital letters, a hyphen, two digits, e.g. FA-06).")
    if "icon" in meta and (not isinstance(meta["icon"], str) or not ID_RE.match(meta["icon"])):
        report.error(p, "icon must be one name from knowledge/icons.yaml, or be left out.")
    if "rhythm" in meta:
        r = meta["rhythm"]
        if not isinstance(r, dict) or set(r) != RHYTHM_FIELDS or not all(isinstance(r[k], int) and not isinstance(r[k], bool) for k in r):
            report.error(p, "rhythm must be {bpm: <number>, from_step: <number>, to_step: <number>}, or be left out.")
        elif not 30 <= r["bpm"] <= 200 or not 1 <= r["from_step"] <= r["to_step"]:
            report.error(p, "rhythm: bpm must be 30-200 and from_step must not be after to_step.")
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
    for m in UK_RE.finditer(text):
        word = m.group(1)
        us = UK_TO_US[word.lower()]
        us = us[0].upper() + us[1:] if word[0].isupper() else us
        report.error(p, f"{where}: '{word}' is British English. Fix: write '{us}' (articles use American English).")


def validate_body(article: Article, report: Report) -> None:
    p, body = article.path, article.body
    english = article.lang == "en"
    section_names = LANGUAGES[article.lang]["sections"]
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
            m = IMAGE_LINE_RE.match(stripped)
            if not m or stripped != line:
                report.error(p, f"{where}: a picture must be on a line of its own, written exactly like "
                                "![what the picture shows](image:<image-id>). Fix: see 'Adding a picture' in AGENTS.md.")
            else:
                alt = m.group(1).strip()
                if not ALT_MIN <= len(alt) <= ALT_MAX or alt.lower() in {"image", "picture", "diagram", "photo"}:
                    report.error(p, f"{where}: the picture's description ('{alt}') must say what it shows, in "
                                    f"{ALT_MIN}-{ALT_MAX} characters, e.g. ![Hands stacked on the center of the chest]"
                                    "(image:cpr-hand-position). People using a screen reader hear only this.")
                article.images.append((m.group(2), n + 1))
        elif re.match(r"^\s+([-*+]|\d+\.)\s", line):
            report.error(p, f"{where}: nested or indented list items are not supported. "
                            "Fix: start every list item at the beginning of the line.")
        elif re.match(r"^([*+\u2022])\s", stripped):
            report.error(p, f"{where}: bullet items must start with '- '. Fix: replace '{stripped[0]}' with '-'.")
        elif re.search(r"<[a-zA-Z/][^>]*>", line):
            report.error(p, f"{where}: HTML is not supported. Fix: write plain text.")
        if english:
            _check_spelling(p, report, where, ARTICLE_LINK_RE.sub(r"\1", line))
    title_line = next((i for i, l in enumerate(file_lines) if l.startswith("title:")), None)
    if title_line is not None and english:
        _check_spelling(p, report, f"line {title_line + 1}", file_lines[title_line])
    check_file_text(p, report)

    if headings != section_names:
        report.error(p, "the body must have exactly these six headings, in this order: "
                        + ", ".join(f"'## {h}'" for h in section_names)
                        + f". Found: {', '.join(repr(h) for h in headings) or 'none'}.")
        return
    first = body.splitlines()[0].strip() if body else ""
    if first != f"## {section_names[0]}":
        report.error(p, f"the body must start with '## {section_names[0]}' right after the header.")
    # From here on, sections are known by their English names whatever the article's language;
    # 'shown' gives the heading as written, for messages.
    article.sections = {name: article.sections.get(local, "") for name, local in zip(REQUIRED_SECTIONS, section_names)}
    shown = dict(zip(REQUIRED_SECTIONS, section_names))

    for name in REQUIRED_SECTIONS:
        text = article.sections.get(name, "")
        if not text:
            report.error(p, f"section '## {shown[name]}' is empty.")
            continue
        lines = [l for l in text.splitlines() if l.strip()]
        if name == "Steps":
            nums = []
            for l in lines:
                if IMAGE_LINE_RE.match(l.strip()):
                    if not nums:
                        report.error(p, f"a picture in '## {shown['Steps']}' must come directly under the step it belongs to, "
                                        "not before the first step.")
                        break
                    continue
                m = re.match(r"^(\d+)\.\s+\S", l)
                if not m:
                    report.error(p, f"'## {shown['Steps']}' may only contain numbered steps ('1. Do this.'). "
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
                article.step_count = len(nums)
                if len(nums) < 2:
                    report.error(p, f"'## {shown['Steps']}' needs at least 2 numbered steps.")
                elif nums != list(range(1, len(nums) + 1)):
                    report.error(p, f"steps must be numbered 1, 2, 3, ... without gaps. Found: {nums}.")
        elif any(IMAGE_LINE_RE.match(l.strip()) for l in lines):
            # Elsewhere, a picture goes directly under the paragraph or bullet it shows. It can't open
            # a section (it would belong to nothing), and '## Get help' has no use for one.
            if name == "Get help":
                report.error(p, f"pictures are not allowed in '## {shown['Get help']}'. Fix: put it directly under the "
                                "step, bullet or paragraph it shows, in another section.")
            elif IMAGE_LINE_RE.match(lines[0].strip()):
                report.error(p, f"a picture can't be the first thing in '## {shown[name]}'. Fix: put it directly under "
                                "the bullet or paragraph it shows.")
        if name in LIST_SECTIONS:
            bad = [l for l in lines if not l.startswith("- ") and not IMAGE_LINE_RE.match(l.strip())]
            if bad:
                report.error(p, f"'## {shown[name]}' may only contain bullet items starting with '- '. "
                                f"Found: '{bad[0].strip()[:50]}'.")


def load_images(path: Path, images_dir: Path, report: Report) -> list[dict]:
    """Reads the image registry. Every picture needs a license, a creator and a source."""
    if not path.exists():
        return []
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as e:
        report.error(path, f"could not be read: {e}")
        return []
    entries = data.get("images") or []
    out, seen = [], set()
    for i, e in enumerate(entries, 1):
        where = f"image {i}" + (f" ('{e.get('id')}')" if isinstance(e, dict) and e.get("id") else "")
        if not isinstance(e, dict):
            report.error(path, f"{where} must have id, file, license, creator, source and ai_generated.")
            continue
        for k in e:
            if k not in IMAGE_FIELDS:
                report.error(path, f"{where} has unknown field '{k}'. Allowed: {', '.join(sorted(IMAGE_FIELDS))}.")
        for k in sorted(IMAGE_FIELDS - set(e)):
            report.error(path, f"{where} is missing '{k}'.")
        iid = e.get("id")
        if not isinstance(iid, str) or not ID_RE.match(iid):
            report.error(path, f"{where}: id must be lowercase words joined by hyphens, e.g. cpr-hand-position.")
            continue
        if iid in seen:
            report.error(path, f"{where}: duplicate id.")
        seen.add(iid)
        if e.get("ai_generated") is not False:
            report.error(path, f"{where}: ai_generated must be false. AI-generated pictures are not allowed: they "
                               "often get hands, anatomy and positions wrong. Fix: use a real diagram or photo.")
        if e.get("license") not in IMAGE_LICENSES:
            report.error(path, f"{where}: license must be one of {sorted(IMAGE_LICENSES)}. If you can't confirm the "
                               "license, don't use the picture.")
        for k in ("creator", "source"):
            if k in e and (not isinstance(e[k], str) or not e[k].strip()):
                report.error(path, f"{where}: {k} is empty.")
        src = e.get("source")
        if isinstance(src, str) and src.strip() and src.startswith("http") and not src.startswith("https://"):
            report.error(path, f"{where}: source must be a full https:// address or a description of the origin.")
        f = images_dir / str(e.get("file", ""))
        if f.suffix not in IMAGE_TYPES:
            report.error(path, f"{where}: file must be .webp (preferred) or .png. Fix: use python -m tools.add_image.")
        elif not f.is_file():
            report.error(path, f"{where}: file {_rel(f)} does not exist.")
        elif f.stat().st_size > IMAGE_MAX_BYTES:
            report.error(path, f"{where}: {f.name} is {f.stat().st_size // 1024} KB; the maximum is "
                               f"{IMAGE_MAX_BYTES // 1024} KB. Fix: add it with python -m tools.add_image, which shrinks it.")
        else:
            out.append({**e, "path": f})
    listed = {str(e.get("file")) for e in entries if isinstance(e, dict)}
    if images_dir.is_dir():
        for f in sorted(images_dir.iterdir()):
            if f.is_file() and not f.name.startswith(".") and f.name not in listed:
                report.error(f, f"this file is not listed in {_rel(path)}. Fix: add it with python -m tools.add_image, or delete it.")
    return out


def check_image_use(articles: list[Article], images: list[dict], images_file: Path, report: Report) -> None:
    known = {i["id"] for i in images}
    used = set()
    for a in articles:
        for iid, line in a.images:
            used.add(iid)
            if iid not in known:
                report.error(a.path, f"line {line}: picture '{iid}' is not in {_rel(images_file)}. "
                                     "Fix: add it with python -m tools.add_image, or check the spelling of the id.")
    for iid in sorted(known - used):
        report.error(images_file, f"picture '{iid}' is not used by any article. Fix: use it in an article, or remove it.")


def load_categories(path: Path, report: Report, icons: dict[str, str] | None = None) -> list[dict]:
    cats = (yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("categories", [])
    codes = set()
    for i, c in enumerate(cats):
        if not isinstance(c, dict) or not c.get("id") or not c.get("title"):
            report.error(path, f"category {i + 1} needs an id and a title")
            continue
        code = str(c.get("code", ""))
        if not re.fullmatch(r"[A-Z]{2}", code):
            report.error(path, f"category '{c['id']}' needs a code of two capital letters, e.g. FA.")
        elif code in codes:
            report.error(path, f"category '{c['id']}': code {code} is already used by another category.")
        codes.add(code)
        if icons is not None and c.get("icon") not in icons:
            report.error(path, f"category '{c['id']}': icon '{c.get('icon')}' is not in knowledge/icons.yaml.")
        titles = c.get("titles", {})
        if not isinstance(titles, dict) or any(l not in LANGUAGES or l == "en" or not isinstance(t, str) or not t.strip()
                                               for l, t in titles.items()):
            report.error(path, f"category '{c['id']}': titles must map a language to the title in it, "
                               "e.g. titles: {nb: Førstehjelp}.")
    return cats


def load_icons(path: Path, report: Report) -> dict[str, str]:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    icons = data.get("icons") or {}
    out = {}
    for name, d in icons.items():
        if not isinstance(name, str) or not ID_RE.match(name) or not isinstance(d, str) \
                or not re.fullmatch(r"[MmLlHhVvCcSsQqTtAaZz0-9 .,\-]+", d):
            report.error(path, f"icon '{name}' must be a lowercase name with SVG path data.")
            continue
        out[name] = d
    return out


def check_codes_icons_rhythm(articles: list[Article], categories: list[dict], icons: dict[str, str], report: Report) -> None:
    prefix = {c.get("id"): c.get("code") for c in categories if isinstance(c, dict)}
    seen: dict[str, Path] = {}
    for a in articles:
        code, cat = a.meta.get("code"), a.meta.get("category")
        if isinstance(code, str) and CODE_RE.match(code):
            if prefix.get(cat) and not code.startswith(prefix[cat] + "-"):
                report.error(a.path, f"code {code} does not match category '{cat}' (codes there start with "
                                     f"{prefix[cat]}-). Fix: keep the code python -m tools.new_article gave it.")
            if code in seen:
                report.error(a.path, f"code {code} is already used by {_rel(seen[code])}. Every article needs its own code.")
            seen.setdefault(code, a.path)
        icon = a.meta.get("icon")
        if isinstance(icon, str) and icon not in icons:
            report.error(a.path, f"icon '{icon}' is not in knowledge/icons.yaml. Choose one of: {', '.join(sorted(icons))}.")
        r = a.meta.get("rhythm")
        if isinstance(r, dict) and isinstance(r.get("to_step"), int) and a.step_count and r["to_step"] > a.step_count:
            report.error(a.path, f"rhythm.to_step is {r['to_step']} but the article has only {a.step_count} steps.")


def load_stopwords(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return sorted({l.strip().lower() for l in lines if l.strip() and not l.startswith("#")})


def load_articles(articles_dir: Path, report: Report, release: bool) -> list[Article]:
    articles: list[Article] = []
    seen: dict[str, Path] = {}
    for path in sorted(articles_dir.glob("*/*.md")):
        if "." in path.stem:  # <id>.<lang>.md: a translation, loaded by load_translations
            continue
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


def load_translations(articles_dir: Path, originals: list[Article], report: Report, release: bool) -> list[Article]:
    """Reads every <category>/<id>.<lang>.md and checks it against its English article."""
    by_id = {a.meta["id"]: a for a in originals if isinstance(a.meta.get("id"), str)}
    others = [l for l in LANGUAGES if l != "en"]
    out: list[Article] = []
    titles: dict[tuple[str, str], Path] = {}
    for path in sorted(articles_dir.glob("*/*.md")):
        if "." not in path.stem:
            continue
        base, _, lang = path.stem.rpartition(".")
        if lang not in others:
            report.error(path, f"'{lang}' is not a language articles can be translated into. Fix: name a translation "
                               f"<id>.<lang>.md, with <lang> one of: {', '.join(others)}.")
            continue
        english = by_id.get(base)
        if english is None or english.path.parent != path.parent:
            report.error(path, f"there is no English article {path.parent.name}/{base}.md. Fix: a translation sits "
                               "in the same folder as its English article, with the same id.")
            continue
        try:
            meta, body = parse_article(path)
        except (ValueError, yaml.YAMLError) as e:
            report.error(path, f"the header could not be read: {e}. Fix: the file must start with a line '---', "
                               "then the header, then '---'. Put any value containing ':' in double quotes.")
            continue
        errors_before = len(report.errors)
        for f in meta:
            if f in INHERITED_FIELDS:
                report.error(path, f"remove '{f}' from the header: a translation always takes it from the English "
                                   f"article ({base}.md).")
            elif f not in TRANSLATION_REQUIRED:
                report.error(path, f"unknown header field '{f}'. Allowed in a translation: "
                                   f"{', '.join(TRANSLATION_REQUIRED)}.")
        for f in TRANSLATION_REQUIRED:
            if f not in meta:
                report.error(path, f"missing header field '{f}'. Fix: a translation's header has exactly: "
                                   f"{', '.join(TRANSLATION_REQUIRED)}.")
        if "lang" in meta and meta["lang"] != lang:
            report.error(path, f"lang must be {lang}, as in the file name.")
        tof, ev = meta.get("translation_of"), english.meta.get("version")
        if "translation_of" in meta and (isinstance(tof, bool) or not isinstance(tof, int) or tof < 1):
            report.error(path, f"translation_of must be the version number of the English article you translated "
                               f"(it is now version {ev}), e.g. translation_of: {ev}.")
            tof = None
        elif isinstance(tof, int) and isinstance(ev, int) and tof > ev:
            report.error(path, f"translation_of is {tof}, but the English article is only at version {ev}.")
        merged = {k: english.meta[k] for k in INHERITED_FIELDS if k in english.meta}
        merged.update({k: v for k, v in meta.items() if k not in ("lang", "translation_of") and k not in INHERITED_FIELDS})
        art = Article(path, merged, body, split_sections(body), lang=lang, translation_of=tof)
        # Drafts are fine: in a release build a translation that isn't reviewed is left out and the
        # English article is shown instead, so it never blocks a release.
        validate(art, report, release=False)
        for m in ANY_LINK_RE.finditer(body):
            target = m.group(2)
            if not target.startswith("article:") or target[len("article:"):] not in by_id:
                report.error(path, f"link '{m.group(0)}' must point to an existing article as (article:<id>), "
                                   "using the same id as in the English article.")
        t = (lang, str(merged.get("title", "")).strip().lower())
        if t[1] and t in titles:
            report.error(path, f"title '{merged['title']}' is already used by {_rel(titles[t])}.")
        titles.setdefault(t, path)
        if len(report.errors) == errors_before:
            # Translate everything, one for one: a missing step or warning could hurt someone.
            if english.step_count and art.step_count != english.step_count:
                report.error(path, f"has {art.step_count} steps, but {base}.md has {english.step_count}. Fix: translate "
                                   "every step, one for one; don't merge, split, add or drop steps.")
            count = lambda a: sum(1 for l in a.sections.get("Warnings", "").splitlines() if l.startswith("- "))
            if count(art) != count(english):
                report.error(path, f"has {count(art)} warnings, but {base}.md has {count(english)}. Fix: translate "
                                   "every warning, one for one.")
            if sorted(i for i, _ in art.images) != sorted(i for i, _ in english.images):
                report.error(path, f"must use the same pictures as {base}.md, in the same places, with the "
                                   "description translated.")
            if isinstance(tof, int) and isinstance(ev, int) and tof < ev:
                report.warn(path, f"outdated: translated from version {tof}, but {base}.md is now version {ev}. "
                                  f"Until it is updated, the app shows the English article. Fix: translate the "
                                  f"changes and set translation_of: {ev}.")
        out.append(art)
    return out


def current_translations(originals: list[Article], translations: list[Article], lang: str,
                         release: bool) -> list[Article]:
    """The articles for one language: the translation where it is up to date (and reviewed, in a
    release build), the English article otherwise."""
    version = {a.id: a.meta.get("version") for a in originals}
    use = {t.id: t for t in translations if t.lang == lang and t.translation_of == version.get(t.id)
           and (not release or t.meta.get("status") == "reviewed")}
    return [use.get(a.id, a) for a in originals]


def db_path(out: Path, lang: str) -> Path:
    """knowledge.db for English, knowledge-<lang>.db for every other language."""
    return out if lang == "en" else out.with_name(f"{out.stem}-{lang}{out.suffix}")


def lang_file(path: Path, lang: str) -> Path:
    """stopwords.txt -> stopwords.nb.txt, synonyms.yaml -> synonyms.nb.yaml."""
    return path.with_name(f"{path.stem}.{lang}{path.suffix}")


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
    text = IMAGE_ANY_RE.sub(" ", markdown)  # pictures are not searchable text
    text = ARTICLE_LINK_RE.sub(r"\1", text)  # index the link text, not the target id
    return re.sub(r"[*_`#>\[\]()]", " ", text)


def _opt_str(v) -> str | None:
    return None if v is None else str(v)


def _json_default(o):
    if isinstance(o, (dt.date, dt.datetime)):
        return o.isoformat()
    raise TypeError(type(o))


def write_db(articles: list[Article], synonyms: list[list[str]], out: Path, release: bool,
             categories: list[dict] = (), stopwords: list[str] = (), images: list[dict] = (),
             icons: dict[str, str] | None = None, lang: str = "en") -> str:
    icons = icons or {}
    tokenize = FTS_TOKENIZE.get(lang, FTS_TOKENIZE_OTHER)
    cat_icon = {c["id"]: c.get("icon") for c in categories}
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.executescript(
        f"""
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE articles (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            category TEXT NOT NULL,
            status TEXT NOT NULL,
            version INTEGER NOT NULL,
            high_risk INTEGER NOT NULL,
            emergency INTEGER NOT NULL,
            license TEXT NOT NULL,
            body_md TEXT NOT NULL,
            situations TEXT NOT NULL,
            keywords TEXT NOT NULL,
            reviewer TEXT,
            review_date TEXT,
            review_by TEXT,
            diagrams_needed TEXT NOT NULL,
            code TEXT NOT NULL,
            icon TEXT,
            lead TEXT NOT NULL,
            rhythm_bpm INTEGER,
            rhythm_from INTEGER,
            rhythm_to INTEGER
        );
        CREATE TABLE icons (name TEXT PRIMARY KEY, path TEXT NOT NULL);
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
            tokenize = '{tokenize}'
        );
        CREATE TABLE synonyms (group_id INTEGER NOT NULL, term TEXT NOT NULL);
        CREATE INDEX synonyms_term ON synonyms(term);
        CREATE TABLE vocab (word TEXT PRIMARY KEY) WITHOUT ROWID;
        CREATE TABLE stopwords (word TEXT PRIMARY KEY) WITHOUT ROWID;
        CREATE TABLE categories (id TEXT PRIMARY KEY, title TEXT NOT NULL, position INTEGER NOT NULL,
                                 code TEXT NOT NULL, icon TEXT);
        CREATE TABLE images (
            id TEXT PRIMARY KEY,
            mime TEXT NOT NULL,
            data BLOB NOT NULL,
            license TEXT NOT NULL,
            creator TEXT NOT NULL,
            source TEXT NOT NULL
        );
        """
    )
    digest = hashlib.sha256()
    vocab: set[str] = set()
    for a in sorted(articles, key=lambda a: a.id):
        m = a.meta
        review = m["review"] or {}
        row = (
            m["id"], m["title"], m["category"], m["status"], m["version"], int(m["high_risk"]),
            int(m.get("emergency") is True), m["license"], a.body,
            json.dumps(m["situations"]), json.dumps(m["keywords"]),
            _opt_str(review.get("reviewer")), _opt_str(review.get("date")), _opt_str(review.get("review_by")),
            json.dumps(m.get("diagrams_needed") or []),
            m["code"], m.get("icon") or cat_icon.get(m["category"]), m["situations"][0],
            *((m["rhythm"]["bpm"], m["rhythm"]["from_step"], m["rhythm"]["to_step"]) if m.get("rhythm") else (None, None, None)),
        )
        con.execute("INSERT INTO articles VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", row)
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
    con.executemany("INSERT INTO categories VALUES (?, ?, ?, ?, ?)",
                    ((c["id"], (c.get("titles") or {}).get(lang, c["title"]), i, c.get("code", ""), c.get("icon"))
                     for i, c in enumerate(categories)))
    con.executemany("INSERT INTO icons VALUES (?, ?)", sorted(icons.items()))
    digest.update(json.dumps(sorted(icons.items())).encode())
    digest.update(json.dumps([list(stopwords), list(categories)]).encode())
    for img in sorted(images, key=lambda i: i["id"]):
        data = img["path"].read_bytes()
        con.execute("INSERT INTO images VALUES (?, ?, ?, ?, ?, ?)",
                    (img["id"], IMAGE_TYPES[img["path"].suffix], data, img["license"], img["creator"], img["source"]))
        digest.update(img["id"].encode() + hashlib.sha256(data).digest())

    content_hash = digest.hexdigest()[:16]
    meta = {
        "schema_version": str(SCHEMA_VERSION),
        "content_hash": content_hash,
        "release": "1" if release else "0",
        "article_count": str(len(articles)),
        "lang": lang,
        "translated_count": str(sum(a.lang == lang for a in articles)),
    }
    con.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
    con.commit()
    con.execute("VACUUM")
    con.close()
    tmp.replace(out)
    return content_hash


def build(out: Path = DEFAULT_OUT, release: bool = False, check_only: bool = False,
          articles_dir: Path = ARTICLES_DIR, synonyms_file: Path = SYNONYMS_FILE,
          categories_file: Path = CATEGORIES_FILE, stopwords_file: Path = STOPWORDS_FILE,
          images_file: Path = IMAGES_FILE, images_dir: Path = IMAGES_DIR, icons_file: Path = ICONS_FILE) -> Report:
    report = Report()
    articles = load_articles(articles_dir, report, release)
    translations = load_translations(articles_dir, articles, report, release)
    images = load_images(images_file, images_dir, report)
    check_image_use(articles + translations, images, images_file, report)
    synonyms = load_synonyms(synonyms_file, report)
    icons = load_icons(icons_file, report)
    categories = load_categories(categories_file, report, icons)
    check_codes_icons_rhythm(articles, categories, icons, report)
    known = {c.get("id") for c in categories if isinstance(c, dict)}
    for a in articles:
        if a.meta.get("category") and a.meta["category"] not in known:
            report.error(a.path, f"category '{a.meta['category']}' is not listed in {_rel(categories_file)}")
    stopwords = load_stopwords(stopwords_file)
    extra = {}
    for lang in LANGUAGES:
        if lang != "en":
            sw, syn = lang_file(stopwords_file, lang), lang_file(synonyms_file, lang)
            extra[lang] = (load_stopwords(sw) if sw.exists() else [], load_synonyms(syn, report) if syn.exists() else [])
    if not report.errors and not check_only:
        write_db(articles, synonyms, out, release, categories, stopwords, images, icons)
        # Every other language: its translations, English where there is none, and its own search
        # words on top of the English ones (people may type either).
        for lang, (sw, syn) in extra.items():
            write_db(current_translations(articles, translations, lang, release), synonyms + syn,
                     db_path(out, lang), release, categories, sorted(set(stopwords) | set(sw)), images, icons, lang)
    return report


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--release", action="store_true", help="require every article to be reviewed")
    ap.add_argument("--check", action="store_true", help="validate only")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--quiet", action="store_true", help="hide warnings")
    ap.add_argument("--knowledge", type=Path, help="build from another knowledge folder (used by tests); "
                                                   "files it lacks fall back to the real ones")
    args = ap.parse_args(argv)

    paths = {}
    if args.knowledge:
        k = args.knowledge.resolve()
        pick = lambda name, default: (k / name) if (k / name).exists() else default
        paths = dict(articles_dir=k / "articles", synonyms_file=pick("synonyms.yaml", SYNONYMS_FILE),
                     categories_file=pick("categories.yaml", CATEGORIES_FILE),
                     stopwords_file=pick("stopwords.txt", STOPWORDS_FILE),
                     images_file=k / "images.yaml", images_dir=k / "images",
                     icons_file=pick("icons.yaml", ICONS_FILE))
    report = build(args.out, args.release, args.check, **paths)
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
