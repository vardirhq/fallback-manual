"""Apply a reviewer's sign-off from the review pack to an article. For the project owner only.

Usage:
    python -m tools.apply_review review-cpr.yaml [review-choking.nb.yaml ...]
    python -m tools.apply_review --dry-run review-cpr.yaml

A file can be the saved sign-off, or any text that contains it (a pasted email, a GitHub issue).
Sign-offs sent from the review site as GitHub issues are applied by .github/workflows/review-signoff.yml,
which runs this tool with --github-user, and opens a pull request for the owner to merge.

A sign-off is applied only if:
- the reviewer is listed in knowledge/reviewers.yaml (the owner adds people there; this tool never does),
- the article has not changed since the reviewer read it (same version and fingerprint),
- the reviewer approved it and confirmed every source.

Applying sets the article (or translation) to status: reviewed, fills in review.reviewer, date and
review_by, and marks every source checked. A sign-off that asks for changes is not applied: its
comments are printed so they can be worked into the article, which then needs a new review.

AI agents must never run this tool. Only the project owner does, with a sign-off a human sent them.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sys
from pathlib import Path

import yaml

from tools import build_kb
from tools.review_pack import fingerprint

FIELDS = {"fallback-manual-review", "article", "lang", "version", "fingerprint", "reviewer", "decision", "date",
          "review_by", "sources_confirmed", "comments"}


class Refused(Exception):
    pass


class ChangesRequested(Refused):
    pass


SIGNOFF_RE = re.compile(r"^fallback-manual-review: 1$.*?(?=^```|\Z)", re.M | re.S)


def _is_signoff(data) -> bool:
    return isinstance(data, dict) and "fallback-manual-review" in data


def signoff_from_text(text: str) -> dict:
    """The sign-off in a saved file, or inside other text (an email, a GitHub issue body)."""
    text = text.replace("\r\n", "\n")
    candidates = [text] + re.findall(r"^```[a-z]*\n(.*?)^```", text, re.M | re.S)
    m = SIGNOFF_RE.search(text)
    if m:
        candidates.append(m.group(0))
    for c in candidates:
        try:
            data = yaml.safe_load(c)
        except yaml.YAMLError:
            continue
        if _is_signoff(data):
            return data
    raise Refused("no sign-off found (it starts with the line 'fallback-manual-review: 1').")


def _date(value, name: str) -> str:
    try:
        return dt.date.fromisoformat(str(value)).isoformat()
    except ValueError:
        raise Refused(f"{name} must be a date as YYYY-MM-DD, not '{value}'.")


def find_article(aid: str, lang: str) -> build_kb.Article:
    report = build_kb.Report()
    articles = build_kb.load_articles(build_kb.ARTICLES_DIR, report, release=False)
    if lang == "en":
        match = [a for a in articles if a.id == aid]
    else:
        match = [t for t in build_kb.load_translations(build_kb.ARTICLES_DIR, articles, report, release=False)
                 if t.id == aid and t.lang == lang]
    if not match:
        raise Refused(f"there is no article '{aid}' in language '{lang}'.")
    return match[0]


def check(signoff: dict, github_user: str | None = None) -> tuple[build_kb.Article, dict]:
    if not isinstance(signoff, dict) or signoff.get("fallback-manual-review") != 1:
        raise Refused("this is not a Fallback Manual review sign-off.")
    unknown = set(signoff) - FIELDS
    if unknown:
        raise Refused(f"unknown fields: {', '.join(sorted(unknown))}.")
    aid, lang = str(signoff.get("article")), str(signoff.get("lang", "en"))
    article = find_article(aid, lang)
    name = f"{aid}" + ("" if lang == "en" else f" ({lang})")
    if signoff.get("decision") == "changes":
        raise ChangesRequested(f"{signoff.get('reviewer')} asked for changes to {name}. Their comments:\n\n"
                      f"{str(signoff.get('comments', '')).rstrip()}\n\n"
                      "Make the changes (that raises the version), then send a new review pack.")
    if signoff.get("decision") != "approve":
        raise Refused("decision must be approve or changes.")
    if signoff.get("version") != article.meta.get("version") or signoff.get("fingerprint") != fingerprint(article):
        raise Refused(f"{name} has changed since the reviewer read it (they read version {signoff.get('version')}, "
                      f"it is now version {article.meta.get('version')}). Send them a new review pack.")
    reviewer = str(signoff.get("reviewer", "")).strip()
    if reviewer not in build_kb.approved_reviewers():
        raise Refused(f"'{reviewer}' is not in knowledge/reviewers.yaml. If you trust this person to review, "
                      "add them there yourself (with their qualification), then run this again.")
    if github_user is not None:
        # Sent from GitHub: anyone can open an issue, so the account must be the reviewer's own.
        record = next(r for r in build_kb.reviewer_records() if str(r["name"]).strip() == reviewer)
        registered = str(record.get("github") or "").strip().lstrip("@").lower()
        if not registered:
            raise Refused(f"'{reviewer}' has no GitHub username in knowledge/reviewers.yaml, so a sign-off from "
                          "GitHub can't be checked. The owner can add it (github: <username>), or apply the "
                          "sign-off by hand.")
        if registered != github_user.strip().lower():
            raise Refused(f"this sign-off names '{reviewer}', but was sent from the GitHub account "
                          f"'{github_user}', not theirs. Only the reviewer themself can send their sign-off.")
    sources = article.meta.get("sources") or []
    confirmed = set(signoff.get("sources_confirmed") or [])
    missing = [i for i in range(len(sources)) if i not in confirmed]
    if not sources or missing:
        raise Refused(f"the reviewer did not confirm every source of {name} "
                      f"(missing: {', '.join(str(sources[i].get('title')) for i in missing) or 'there are none'}).")
    date = _date(signoff.get("date"), "date")
    review_by = _date(signoff.get("review_by"), "review_by")
    if review_by <= date:
        raise Refused("review_by must be after the review date.")
    return article, {"reviewer": reviewer, "date": date, "review_by": review_by}


def apply(article: build_kb.Article, review: dict) -> str:
    """The article file's new text: the header is edited line by line, so its layout is kept."""
    text = article.path.read_text(encoding="utf-8")
    head_end = text.index("\n---", 3)
    head, rest = text[:head_end], text[head_end:]
    head, n = re.subn(r"^status: draft$", "status: reviewed", head, count=1, flags=re.M)
    if n != 1:
        raise Refused(f"{build_kb._rel(article.path)} is not a draft with a 'status: draft' line.")
    for key in ("reviewer", "date", "review_by"):
        value = yaml.safe_dump(review[key], default_style='"').strip() if key == "reviewer" else review[key]
        head, n = re.subn(rf"^(  {key}:) null$", rf"\g<1> {value}", head, count=1, flags=re.M)
        if n != 1:
            raise Refused(f"{build_kb._rel(article.path)}: could not find 'review: {key}: null' to fill in.")
    head = re.sub(r"^(\s+checked:) false$", r"\1 true", head, flags=re.M)
    return head + rest


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("signoffs", nargs="+", type=Path)
    ap.add_argument("--dry-run", action="store_true", help="check the sign-offs, change nothing")
    ap.add_argument("--github-user", help="the GitHub account that sent the sign-off; it must be the reviewer's")
    ap.add_argument("--result", type=Path, help="write the outcome of the (single) sign-off here, as JSON")
    args = ap.parse_args(argv)
    failed = 0
    result: dict = {}
    for path in args.signoffs:
        try:
            signoff = signoff_from_text(path.read_text(encoding="utf-8"))
            if isinstance(signoff, dict):
                result = {"article": str(signoff.get("article")), "lang": str(signoff.get("lang", "en")),
                          "reviewer": str(signoff.get("reviewer", "")).strip()}
            article, review = check(signoff, args.github_user)
            new = apply(article, review)
            if not args.dry_run:
                article.path.write_text(new, encoding="utf-8")
            verb = "Would mark" if args.dry_run else "Marked"
            message = (f"{verb} {build_kb._rel(article.path)} reviewed by {review['reviewer']} "
                       f"(review again by {review['review_by']}).")
            result.update(outcome="applied", file=build_kb._rel(article.path), version=article.meta.get("version"),
                          message=message)
            print(f"{path}: {message}")
        except ChangesRequested as e:
            failed += 1
            result.update(outcome="changes", message=str(e))
            print(f"{path}: not applied: {e}", file=sys.stderr)
        except (Refused, yaml.YAMLError, OSError) as e:
            failed += 1
            result.update(outcome="refused", message=str(e))
            print(f"{path}: not applied: {e}", file=sys.stderr)
    if args.result:
        import json
        args.result.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    if not args.dry_run and failed < len(args.signoffs):
        print("Now run: python -m tools.check")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
