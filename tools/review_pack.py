"""Build a review pack: one offline web page per article, for a human expert to review and sign off.

Usage:
    python -m tools.review_pack                  # writes build/review/index.html and one page per article
    python -m tools.review_pack --out <folder>
    python -m tools.review_pack --only cpr,choking   # just these articles (all their languages)

The pages need no server and no git. On every push to main, .github/workflows/review-site.yml
publishes them to GitHub Pages, so a reviewer just gets a link; the folder also works offline, from
a zip. Every page carries a noindex tag and a "draft, not for emergencies" banner. Each page shows the article as readers see it, its sources with links, a checklist and a
sign-off form. The reviewer saves or copies the sign-off and sends it back; the project owner then
applies it with  python -m tools.apply_review <sign-off file>.

A sign-off names the article's version and a fingerprint of its text, so it can only be applied to
exactly the text the reviewer read.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import html
import json
import os
import re
import sys
from pathlib import Path

from tools import build_kb

DEFAULT_OUT = build_kb.ROOT / "build" / "review"

CHECKLIST = [
    "Every instruction, number, time and amount matches the listed sources.",
    "The steps are in a safe order, and nothing needed for safety is missing.",
    "The warnings cover the main dangers, and say when to call for help.",
    "\"When to use\" describes the situation and its signs correctly.",
    "Pictures, if any, show the right thing.",
]
TRANSLATION_CHECK = "The translation says the same as the English article: nothing added, nothing left out."


BANNER = ('<p class="banner">Draft for expert review. Not checked yet: do not use it in an emergency. '
          'The checked manual is in the Fallback Manual app.</p>')


# Where "Send review on GitHub" opens a new issue. In GitHub Actions this is the repository the
# site is built from; locally, the project's own.
REPO = os.environ.get("GITHUB_REPOSITORY") or "vardirhq/fallback-manual"


def fingerprint(article: build_kb.Article) -> str:
    """What a reviewer approves: the title, the text and the sources. Changing any of them (or the
    version) makes an older sign-off invalid."""
    m = article.meta
    sources = [{k: s.get(k) for k in ("title", "publisher", "url")} for s in (m.get("sources") or []) if isinstance(s, dict)]
    data = {"id": m.get("id"), "lang": article.lang, "version": m.get("version"), "title": m.get("title"),
            "body": article.body.strip(), "sources": sources}
    return hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()[:16]


# --- the article text, as HTML ------------------------------------------------------------------

def _inline(text: str, lang: str, ids: set[str]) -> str:
    out = html.escape(text)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)

    def link(m: re.Match) -> str:
        label, target = m.group(1), m.group(2)
        if target in ids:
            return f'<a href="{page_name(target, lang)}">{label}</a>'
        return label
    return re.sub(r"\[([^\]]+)\]\(article:([a-z0-9-]+)\)", link, out)


def render_body(body: str, lang: str, ids: set[str], images: dict[str, dict], images_dir: Path) -> str:
    parts: list[str] = []
    open_list: str | None = None

    def close() -> None:
        nonlocal open_list
        if open_list:
            parts.append(f"</{open_list}>")
            open_list = None

    for raw in body.splitlines():
        line = raw.strip()
        if not line:
            continue
        img = re.match(r"^!\[([^\]]+)\]\(image:([a-z0-9-]+)\)$", line)
        step = re.match(r"^(\d+)\.\s+(.*)$", line)
        bullet = re.match(r"^-\s+(.*)$", line)
        if line.startswith("## "):
            close()
            parts.append(f"<h2>{html.escape(line[3:])}</h2>")
        elif img:
            alt, iid = img.group(1), img.group(2)
            info = images.get(iid, {})
            src = ""
            f = images_dir / str(info.get("file", ""))
            if info and f.is_file():
                src = "data:image/webp;base64," + base64.b64encode(f.read_bytes()).decode()
            credit = html.escape(f"{info.get('creator', '?')}, {info.get('license', '?')}")
            figure = (f'<figure><img src="{src}" alt="{html.escape(alt)}"><figcaption>{html.escape(alt)}'
                      f'<br><small>Picture: {credit}</small></figcaption></figure>')
            if open_list:
                parts[-1] = parts[-1].removesuffix("</li>") + figure + "</li>"
            else:
                parts.append(figure)
        elif step:
            if open_list != "ol":
                close()
                parts.append("<ol>")
                open_list = "ol"
            parts.append(f'<li value="{step.group(1)}">{_inline(step.group(2), lang, ids)}</li>')
        elif bullet:
            if open_list != "ul":
                close()
                parts.append("<ul>")
                open_list = "ul"
            parts.append(f"<li>{_inline(bullet.group(1), lang, ids)}</li>")
        elif open_list:  # a list item continued on the next line
            parts[-1] = parts[-1].removesuffix("</li>") + " " + _inline(line, lang, ids) + "</li>"
        else:
            parts.append(f"<p>{_inline(line, lang, ids)}</p>")
    close()
    return "\n".join(parts)


# --- pages --------------------------------------------------------------------------------------

def page_name(aid: str, lang: str) -> str:
    return f"{aid}.html" if lang == "en" else f"{aid}.{lang}.html"


STYLE = """
:root { --ink:#16181b; --muted:#5b6066; --bg:#f4f1ea; --card:#fff; --rule:#d9d4c7; --orange:#ff5a1f; --ok:#2e6b4f; --bad:#a3261b; }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font:17px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 760px; margin: 0 auto; padding: 24px 16px 64px; }
h1 { font-size: 30px; line-height: 1.15; margin: 8px 0 4px; }
h2 { font-size: 14px; letter-spacing: .08em; text-transform: uppercase; margin: 28px 0 8px; color: var(--muted); }
.card { background: var(--card); border: 1px solid var(--rule); border-radius: 12px; padding: 18px 20px; margin: 16px 0; }
.meta { color: var(--muted); font-size: 14px; }
.tag { display:inline-block; font-size:12px; font-weight:700; letter-spacing:.04em; padding:2px 8px; border-radius:6px; margin-right:6px; background:#e9e4d8; }
.tag.risk { background: var(--orange); color: #16181b; }
.tag.reviewed { background: #d5eadf; color: var(--ok); }
.article h2 { color: var(--ink); border-bottom: 2px solid var(--rule); padding-bottom: 4px; }
.article li { margin: 6px 0; }
figure { margin: 10px 0; }
figure img { max-width: 100%; border-radius: 8px; border: 1px solid var(--rule); }
figcaption { font-size: 14px; color: var(--muted); }
label { display: block; margin: 8px 0; }
input[type=text], input[type=date], textarea { width: 100%; font: inherit; padding: 8px 10px; border: 1px solid var(--rule); border-radius: 8px; background: #fff; }
textarea { min-height: 110px; }
button { font: inherit; font-weight: 700; padding: 10px 16px; border-radius: 8px; border: 2px solid var(--ink); background: var(--ink); color: #fff; cursor: pointer; margin: 4px 8px 4px 0; }
button.secondary { background: transparent; color: var(--ink); }
pre { white-space: pre-wrap; background: #f0ece2; border-radius: 8px; padding: 12px; font-size: 14px; }
.problem { color: var(--bad); font-weight: 600; }
.banner { background: #ffe39a; color: #16181b; border-radius: 10px; padding: 10px 14px; font-weight: 600; font-size: 15px; }
table { border-collapse: collapse; width: 100%; font-size: 15px; }
td, th { text-align: left; padding: 7px 8px; border-bottom: 1px solid var(--rule); vertical-align: top; }
a { color: #8a3a12; }
@media (prefers-color-scheme: dark) {
  :root { --ink:#eeeae0; --muted:#a8a49a; --bg:#121416; --card:#1b1e21; --rule:#33373b; }
  .tag { background:#2a2e32; } input[type=text], input[type=date], textarea { background:#121416; color:var(--ink); }
  pre { background:#202326; } a { color:#ff9a6b; } button { background:#eeeae0; color:#121416; border-color:#eeeae0; }
  button.secondary { background:transparent; color:var(--ink); }
}
"""

SCRIPT = """
function signoff() {
  const f = document.getElementById('form');
  const d = JSON.parse(document.getElementById('data').textContent);
  const name = f.reviewer.value.trim();
  const decision = f.decision.value;
  const problems = [];
  if (!name) problems.push('Write your name as the project owner registered it.');
  if (!decision) problems.push('Choose approve or changes needed.');
  const sources = [...f.querySelectorAll('input[name=source]')].map(x => x.checked);
  const checks = [...f.querySelectorAll('input[name=check]')].map(x => x.checked);
  if (decision === 'approve') {
    if (sources.some(x => !x)) problems.push('To approve, confirm every source (or choose changes needed and say which source is wrong).');
    if (checks.some(x => !x)) problems.push('To approve, tick every checklist item (or choose changes needed).');
    if (!f.review_by.value) problems.push('Choose a date by which the article should be reviewed again.');
  }
  if (decision === 'changes' && !f.comments.value.trim()) problems.push('Say what needs to change.');
  const out = document.getElementById('out');
  const err = document.getElementById('problems');
  err.innerHTML = problems.map(p => '<div class="problem">' + p + '</div>').join('');
  if (problems.length) { out.textContent = ''; return null; }
  const lines = [
    'fallback-manual-review: 1',
    'article: ' + d.id, 'lang: ' + d.lang, 'version: ' + d.version, 'fingerprint: ' + d.fingerprint,
    'reviewer: ' + JSON.stringify(name),
    'decision: ' + decision,
    'date: ' + new Date().toISOString().slice(0, 10),
  ];
  if (decision === 'approve') lines.push('review_by: ' + f.review_by.value);
  lines.push('sources_confirmed: [' + sources.map((x, i) => x ? i : null).filter(x => x !== null).join(', ') + ']');
  lines.push('comments: |');
  (f.comments.value.trim() || '(none)').split('\\n').forEach(l => lines.push('  ' + l));
  const text = lines.join('\\n') + '\\n';
  out.textContent = text;
  return text;
}
function save() {
  const text = signoff(); if (!text) return;
  const d = JSON.parse(document.getElementById('data').textContent);
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([text], {type: 'text/yaml'}));
  a.download = 'review-' + d.id + (d.lang === 'en' ? '' : '.' + d.lang) + '.yaml';
  a.click();
}
function sendGithub() {
  const text = signoff(); if (!text) return;
  const d = JSON.parse(document.getElementById('data').textContent);
  const title = 'Review: ' + d.id + (d.lang === 'en' ? '' : ' (' + d.lang + ')') + ', version ' + d.version;
  const body = 'Sent from the Fallback Manual review site. Please do not edit the block below.\\n\\n' +
    '```yaml\\n' + text + '```\\n';
  window.open('https://github.com/' + d.repo + '/issues/new?labels=review&title=' + encodeURIComponent(title) +
    '&body=' + encodeURIComponent(body), '_blank', 'noopener');
}
function copyText() {
  const text = signoff(); if (!text) return;
  navigator.clipboard.writeText(text).then(() => { document.getElementById('copied').textContent = 'Copied.'; },
    () => { document.getElementById('copied').textContent = 'Copy failed: select the text below and copy it by hand.'; });
}
"""


def article_page(a: build_kb.Article, english: build_kb.Article | None, ids: set[str], images: dict, images_dir: Path,
                 next_review: str) -> str:
    m = a.meta
    lang = a.lang
    title = html.escape(str(m.get("title", "")))
    tags = []
    if m.get("high_risk"):
        tags.append('<span class="tag risk">HIGH RISK</span>')
    if m.get("emergency"):
        tags.append('<span class="tag risk">ON HOME SCREEN</span>')
    status = m.get("status")
    tags.append(f'<span class="tag{" reviewed" if status == "reviewed" else ""}">{html.escape(str(status)).upper()}</span>')
    lang_name = build_kb.LANGUAGES[lang]["name"]
    meta_line = f'{html.escape(str(m.get("code", "")))} · version {m.get("version")} · {lang_name}'
    if english is not None:
        meta_line += (f' · translation of the English version {a.translation_of} '
                      f'(<a href="{page_name(a.id, "en")}">open the English article</a>)')
    if status == "reviewed":
        r = m.get("review") or {}
        meta_line += f' · reviewed by {html.escape(str(r.get("reviewer")))} on {r.get("date")}'

    sources_html = []
    for i, s in enumerate(m.get("sources") or []):
        url = s.get("url")
        link = (f'<a href="{html.escape(url)}" target="_blank" rel="noopener noreferrer">{html.escape(url)}</a>'
                if url else "<em>no address given: find the original before confirming</em>")
        sources_html.append(
            f'<label><input type="checkbox" name="source"> <strong>{html.escape(str(s.get("title")))}</strong>, '
            f'{html.escape(str(s.get("publisher")))}<br><small>{link}</small><br>'
            f'<small>I opened this source, and the article agrees with it.</small></label>')
    checks = CHECKLIST + ([TRANSLATION_CHECK] if english is not None else [])
    checks_html = "".join(f'<label><input type="checkbox" name="check"> {html.escape(c)}</label>' for c in checks)
    data = json.dumps({"id": a.id, "lang": lang, "version": m.get("version"), "fingerprint": fingerprint(a),
                       "repo": REPO})

    return f"""<!doctype html>
<html lang="{lang}">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Review: {title}</title><style>{STYLE}</style></head>
<body><main>
{BANNER}
<p class="meta"><a href="index.html">All articles</a></p>
<div>{"".join(tags)}</div>
<h1>{title}</h1>
<p class="meta">{meta_line}</p>

<div class="card">
<strong>How to review.</strong> Read the article below as someone in an emergency would. Open each
source and check that the article agrees with it. Then fill in the form at the bottom and press
"Send review on GitHub" (you need a GitHub account, and to submit the page that opens). No GitHub
account? Save or copy your sign-off and send it to the project owner instead. If anything is wrong
or unclear, choose "changes needed" and say what: that is just as useful as an approval.
</div>

<div class="card article">
{render_body(a.body, lang, ids, images, images_dir)}
</div>

<form id="form" class="card" onsubmit="return false">
<h2>Sources</h2>
{"".join(sources_html) or '<p class="problem">This article lists no sources.</p>'}
<h2>Checklist</h2>
{checks_html}
<h2>Sign-off</h2>
<label>Your name, exactly as the project owner registered you<input type="text" name="reviewer" autocomplete="name"></label>
<label><input type="radio" name="decision" value="approve"> Approve: this article is correct and safe to publish.</label>
<label><input type="radio" name="decision" value="changes"> Changes needed (say what below).</label>
<label>Review again by<input type="date" name="review_by" value="{next_review}"></label>
<label>Comments, corrections or concerns<textarea name="comments"></textarea></label>
<div id="problems"></div>
<button type="button" onclick="sendGithub()">Send review on GitHub</button>
<button type="button" class="secondary" onclick="save()">Save sign-off file</button>
<button type="button" class="secondary" onclick="copyText()">Copy sign-off text</button>
<span id="copied" class="meta"></span>
<pre id="out"></pre>
</form>
<p class="meta">Fingerprint {fingerprint(a)}. If the article changes, this pack no longer applies; ask for a new one.</p>
</main>
<script id="data" type="application/json">{html.escape(data, quote=False)}</script>
<script>{SCRIPT}</script>
</body></html>
"""


def index_page(entries: list[tuple[build_kb.Article, build_kb.Article | None]]) -> str:
    def order(e):
        a = e[0]
        return (not a.meta.get("emergency"), not a.meta.get("high_risk"), str(a.meta.get("code")), a.lang)
    rows = []
    for a, english in sorted(entries, key=order):
        m = a.meta
        risk = "Home screen" if m.get("emergency") else ("High risk" if m.get("high_risk") else "")
        rows.append(
            f'<tr><td>{html.escape(str(m.get("code")))}</td>'
            f'<td><a href="{page_name(a.id, a.lang)}">{html.escape(str(m.get("title")))}</a></td>'
            f'<td>{build_kb.LANGUAGES[a.lang]["name"]}</td><td>{risk}</td><td>{html.escape(str(m.get("status")))}</td></tr>')
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Fallback Manual review pack</title><style>{STYLE}</style></head>
<body><main>
{BANNER}
<h1>Fallback Manual: review pack</h1>
<div class="card">
Fallback Manual is a free, offline emergency manual for phones. People read these articles in real
emergencies, so every article is checked by a qualified person before it is published. Thank you for
helping. Open an article, read it, check its sources, and fill in the sign-off form at the bottom of
its page. Articles shown on the app's home screen come first: they matter most.
</div>
<table><tr><th>Code</th><th>Article</th><th>Language</th><th></th><th>Status</th></tr>
{"".join(rows)}
</table>
</main></body></html>
"""


def build_pack(out: Path = DEFAULT_OUT, only: set[str] | None = None) -> int:
    report = build_kb.Report()
    articles = build_kb.load_articles(build_kb.ARTICLES_DIR, report, release=False)
    translations = build_kb.load_translations(build_kb.ARTICLES_DIR, articles, report, release=False)
    images_raw = build_kb.load_images(build_kb.IMAGES_FILE, build_kb.IMAGES_DIR, report)
    if report.errors:
        for e in report.errors:
            print(f"error: {e}", file=sys.stderr)
        print("Fix the articles first (python -m tools.check).", file=sys.stderr)
        return 1
    images = {i["id"]: i for i in images_raw} if isinstance(images_raw, list) else dict(images_raw)
    by_id = {a.id: a for a in articles}
    # A translation is only worth reviewing when it is up to date with the English article.
    current = [t for t in translations if t.translation_of == by_id[t.id].meta.get("version")]
    entries = [(a, None) for a in articles] + [(t, by_id[t.id]) for t in current]
    if only:
        entries = [e for e in entries if e[0].id in only]
    ids = {a.id for a, _ in entries}
    out.mkdir(parents=True, exist_ok=True)
    import datetime as dt
    next_review = (dt.date.today() + dt.timedelta(days=365)).isoformat()
    for a, english in entries:
        (out / page_name(a.id, a.lang)).write_text(
            article_page(a, english, ids, images, build_kb.IMAGES_DIR, next_review), encoding="utf-8")
    (out / "index.html").write_text(index_page(entries), encoding="utf-8")
    print(f"Wrote {len(entries)} article pages and index.html to {out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--only", help="comma-separated article ids")
    args = ap.parse_args(argv)
    only = {s.strip() for s in args.only.split(",")} if args.only else None
    return build_pack(args.out, only)


if __name__ == "__main__":
    sys.exit(main())
