"""Create a new article file with every required part in place, ready to fill in.

Usage:
    python -m tools.new_article --list
    python -m tools.new_article --category water --id water-filtration --title "Filtering water"
    python -m tools.new_article --category first-aid --id choking --title "Choking (adults)" --high-risk

Every line containing TODO must then be replaced. `python -m tools.check` reports any left.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
ARTICLES = ROOT / "knowledge" / "articles"
TEMPLATE = ROOT / "knowledge" / "article-template.md"
CATEGORIES = ROOT / "knowledge" / "categories.yaml"
ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")


def categories() -> list[dict]:
    return yaml.safe_load(CATEGORIES.read_text(encoding="utf-8"))["categories"]


def existing() -> dict[str, tuple[str, str]]:
    """id -> (category, title) for every article."""
    out = {}
    for p in sorted(ARTICLES.glob("*/*.md")):
        m = re.search(r"^title:\s*(.+)$", p.read_text(encoding="utf-8"), re.M)
        out[p.stem] = (p.parent.name, m.group(1).strip() if m else "")
    return out


def next_code(category: str) -> str:
    """The next free code in a category, e.g. FA-06. Codes are never reused or renumbered."""
    prefix = next(c["code"] for c in categories() if c["id"] == category)
    used = []
    for p in ARTICLES.glob("*/*.md"):
        m = re.search(rf"^code:\s*{prefix}-(\d+)\s*$", p.read_text(encoding="utf-8"), re.M)
        if m:
            used.append(int(m.group(1)))
    return f"{prefix}-{max(used, default=0) + 1:02d}"


def show_list() -> None:
    print("Categories (use the id):")
    for c in categories():
        print(f"  {c['id']:<22} {c['title']}")
    icons = yaml.safe_load((ROOT / "knowledge" / "icons.yaml").read_text(encoding="utf-8"))["icons"]
    print("\nIcons (optional: add  icon: <name>  to an article's header; otherwise it uses its category's icon):")
    print("  " + ", ".join(sorted(icons)))
    print("\nExisting articles (link to them with [text](article:<id>)):")
    for aid, (cat, title) in existing().items():
        print(f"  {aid:<28} {cat:<22} {title}")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--list", action="store_true", help="show categories and existing articles")
    ap.add_argument("--category")
    ap.add_argument("--id", dest="aid")
    ap.add_argument("--title")
    ap.add_argument("--high-risk", action="store_true",
                    help="for anything where a mistake could seriously injure or kill (first aid, poisoning, ...)")
    args = ap.parse_args(argv)

    if args.list:
        show_list()
        return 0
    if not (args.category and args.aid and args.title):
        ap.error("--category, --id and --title are all required (or use --list)")

    cat_ids = [c["id"] for c in categories()]
    problems = []
    if args.category not in cat_ids:
        problems.append(f"unknown category '{args.category}'. Choose one of: {', '.join(cat_ids)}")
    if not ID_RE.match(args.aid):
        problems.append(f"id '{args.aid}' is invalid: use lowercase words joined by hyphens, e.g. water-filtration")
    if args.aid in existing():
        cat, title = existing()[args.aid]
        problems.append(f"an article with id '{args.aid}' already exists: {cat}/{args.aid}.md ({title}). "
                        "Edit that file instead, or choose another id")
    if len(args.title) > 60 or args.title.endswith("."):
        problems.append("title must be at most 60 characters and must not end with a full stop")
    if problems:
        for p in problems:
            print(f"error: {p}", file=sys.stderr)
        return 1

    title = args.title if not re.search(r"[:#'\"]", args.title) else '"' + args.title.replace('"', "'") + '"'
    text = TEMPLATE.read_text(encoding="utf-8").format(
        id=args.aid, title=title, category=args.category, code=next_code(args.category),
        high_risk="true" if args.high_risk else "false",
    )
    out = ARTICLES / args.category / f"{args.aid}.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(f"Created {out.relative_to(ROOT)}")
    print("Next: replace every TODO in that file, add questions to evaluation/questions.yaml, "
          "then run: python -m tools.check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
