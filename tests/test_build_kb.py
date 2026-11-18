import sqlite3
import textwrap
from pathlib import Path

import pytest

from tools import build_kb

GOOD_BODY = textwrap.dedent("""
    ## When to use
    When it applies.

    ## What you need
    - Things

    ## Steps
    1. Do this.
    2. Then this.

    ## Warnings
    - Careful.

    ## Common mistakes
    - Oops.

    ## Get help
    Call someone.
""")


def article(status="draft", reviewer=None, body=GOOD_BODY, id_="test-article", **overrides):
    meta = {
        "id": id_, "title": "Test article", "category": "first-aid", "status": status,
        "version": 1, "high_risk": False, "license": "CC-BY-4.0",
        "situations": ["something happened"], "keywords": ["test"],
        "sources": [{"title": "A source", "publisher": "A publisher", "checked": True}],
        "review": {"reviewer": reviewer, "date": "2026-01-01" if reviewer else None,
                   "review_by": "2099-01-01" if reviewer else None},
    }
    meta.update(overrides)
    import yaml
    return "---\n" + yaml.safe_dump(meta, sort_keys=False) + "---\n" + body


@pytest.fixture
def kb(tmp_path: Path):
    articles = tmp_path / "articles" / "first-aid"
    articles.mkdir(parents=True)
    syn = tmp_path / "synonyms.yaml"
    syn.write_text("groups:\n  - [bleeding, blood]\n")

    def make(text: str, name: str = "test-article.md", release=False):
        (articles / name).write_text(text)
        out = tmp_path / "kb.db"
        report = build_kb.build(out, release=release, articles_dir=tmp_path / "articles",
                                synonyms_file=syn)
        return report, out
    return make


def test_valid_draft_builds_with_warning(kb):
    report, out = kb(article())
    assert report.errors == []
    assert any("draft" in w for w in report.warnings)
    con = sqlite3.connect(out)
    assert con.execute("SELECT id, status FROM articles").fetchall() == [("test-article", "draft")]
    assert con.execute("SELECT count(*) FROM articles_fts WHERE articles_fts MATCH 'something'").fetchone() == (1,)
    assert con.execute("SELECT count(*) FROM synonyms").fetchone() == (2,)


def test_release_rejects_drafts(kb):
    report, out = kb(article(), release=True)
    assert any("release" in e for e in report.errors)
    assert not out.exists()


def test_release_accepts_reviewed(kb):
    report, _ = kb(article(status="reviewed", reviewer="Dr. Example, MD"), release=True)
    assert report.errors == []


def test_reviewed_requires_reviewer(kb):
    report, _ = kb(article(status="reviewed"))
    assert any("reviewer" in e for e in report.errors)


def test_missing_section_is_error(kb):
    report, _ = kb(article(body=GOOD_BODY.replace("## Warnings", "## Notes")))
    assert any("Warnings" in e for e in report.errors)


def test_steps_must_be_numbered(kb):
    report, _ = kb(article(body=GOOD_BODY.replace("1. Do this.\n2. Then this.", "- Do this.")))
    assert any("numbered" in e for e in report.errors)


def test_id_must_match_filename(kb):
    report, _ = kb(article(id_="other-name"))
    assert any("file name" in e for e in report.errors)


def test_sources_required(kb):
    report, _ = kb(article(sources=[]))
    assert any("source" in e for e in report.errors)


def test_unchecked_source_warns(kb):
    report, _ = kb(article(sources=[{"title": "S", "publisher": "P", "checked": False}]))
    assert report.errors == []
    assert any("not been checked" in w for w in report.warnings)


def test_real_knowledge_base_is_valid():
    report = build_kb.build(check_only=True)
    assert report.errors == []


def test_links_must_point_to_existing_articles(kb):
    report, _ = kb(article(body=GOOD_BODY.replace("Then this.", "Then see [CPR](article:cpr).")))
    assert any("does not exist" in e for e in report.errors)


def test_links_must_be_article_links(kb):
    report, _ = kb(article(body=GOOD_BODY.replace("Then this.", "Then see [this](https://example.com).")))
    assert any("must point to another article" in e for e in report.errors)


def test_valid_link_builds_and_indexes_link_text_only(kb, tmp_path):
    other = article(id_="other-article").replace("title: Test article", "title: Other")
    (tmp_path / "articles" / "first-aid" / "other-article.md").write_text(other)
    report, out = kb(article(body=GOOD_BODY.replace("Then this.", "Then see [the other one](article:other-article).")))
    assert report.errors == []
    con = sqlite3.connect(out)
    hits = lambda q: con.execute("SELECT count(*) FROM articles_fts WHERE articles_fts MATCH ?", (q,)).fetchone()[0]
    assert hits('body : "the other one"') == 1
    assert hits('body : article') == 0
