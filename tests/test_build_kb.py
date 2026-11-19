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
        "id": id_, "title": "Test article", "category": "first-aid", "code": "FA-01", "status": status,
        "version": 1, "high_risk": False, "license": "CC-BY-4.0",
        "situations": ["something happened", "something else happened"], "keywords": ["test", "example", "sample"],
        "sources": [{"title": "A source", "publisher": "A publisher", "url": "https://example.org/a", "checked": False}],
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
        # Own (empty) picture registry: test articles must not depend on the real manual's pictures.
        report = build_kb.build(out, release=release, articles_dir=tmp_path / "articles",
                                synonyms_file=syn, images_file=tmp_path / "images.yaml",
                                images_dir=tmp_path / "images")
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


def test_release_accepts_reviewed(kb, tmp_path, monkeypatch):
    reviewers = tmp_path / "reviewers.yaml"
    reviewers.write_text("reviewers:\n  - name: Dr. Example, MD\n    qualification: Physician\n")
    monkeypatch.setattr(build_kb, "REVIEWERS_FILE", reviewers)
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
    other = article(id_="other-article", code="FA-02").replace("title: Test article", "title: Other")
    (tmp_path / "articles" / "first-aid" / "other-article.md").write_text(other)
    report, out = kb(article(body=GOOD_BODY.replace("Then this.", "Then see [the other one](article:other-article).")))
    assert report.errors == []
    con = sqlite3.connect(out)
    hits = lambda q: con.execute("SELECT count(*) FROM articles_fts WHERE articles_fts MATCH ?", (q,)).fetchone()[0]
    assert hits('body : "the other one"') == 1
    assert hits('body : article') == 0


# --- rules that protect against typical mistakes by people and AI models ---------------------

def _errors(kb, text):
    report, _ = kb(text)
    return " | ".join(report.errors)


def test_misspelled_field_gets_a_suggestion(kb):
    text = article().replace("situations:", "situation:")
    assert "Did you mean 'situations'" in _errors(kb, text)


def test_ai_cannot_mark_its_own_work_reviewed(kb):
    errs = _errors(kb, article(status="reviewed", reviewer="ChatGPT"))
    assert "not in knowledge/reviewers.yaml" in errs


def test_checked_sources_not_allowed_in_drafts(kb):
    errs = _errors(kb, article(sources=[{"title": "S", "publisher": "P", "url": None, "checked": True}]))
    assert "checked: true in a draft" in errs


def test_draft_must_not_fill_in_review(kb):
    text = article().replace("reviewer: null", "reviewer: Someone")
    assert "draft must have reviewer" in _errors(kb, text)


@pytest.mark.parametrize("junk,expected", [
    ("Boil it. citeturn0search3", "ChatGPT citation marker"),
    ("Boil it. 【source】", "citation bracket"),
    ("Boil it [1].", "numbered citation"),
    ("As an AI, I suggest boiling.", "chatbot wording"),
    ("TODO write this", "unfinished template text"),
])
def test_chatgpt_artifacts_are_rejected(kb, junk, expected):
    errs = _errors(kb, article(body=GOOD_BODY.replace("When it applies.", junk)))
    assert expected in errs and "line(s)" in errs


def test_tracking_parameters_in_urls_are_rejected(kb):
    src = [{"title": "S", "publisher": "P", "url": "https://example.org/x?utm_source=chatgpt.com", "checked": False}]
    assert "utm_source" in _errors(kb, article(sources=src))


@pytest.mark.parametrize("change,expected", [
    (("## Warnings", "# Warnings"), "only '## ' headings"),
    (("- Careful.", "| a | b |"), "tables are not supported"),
    (("- Careful.", "  - Careful."), "nested or indented"),
    (("- Careful.", "* Careful."), "must start with '- '"),
    (("- Careful.", "![pic](x.png)"), "written exactly like"),
    (("2. Then this.", "3. Then this."), "without gaps"),
    (("## Get help", "## Notes"), "exactly these six headings"),
])
def test_body_format_problems_are_caught(kb, change, expected):
    assert expected in _errors(kb, article(body=GOOD_BODY.replace(*change)))


def test_high_risk_needs_two_publishers(kb):  # the fixture has one publisher
    assert "at least 2 different publishers" in _errors(kb, article(high_risk=True))


def test_all_problems_reported_in_one_pass(kb):
    text = article(id_="test-article", license="MIT", version="1").replace("situations:", "situation:")
    errs = _errors(kb, text)
    assert "license" in errs and "version" in errs and "situation" in errs


def test_titles_must_name_the_problem(kb):
    errs = _errors(kb, article().replace("title: Test article", "title: Recognizing and responding to dehydration"))
    assert "name the problem itself" in errs


def test_british_english_is_rejected_in_text_but_allowed_in_keywords(kb):
    errs = _errors(kb, article(body=GOOD_BODY.replace("When it applies.", "Check the colour of the skin.")))
    assert "'colour' is British English" in errs and "'color'" in errs
    errs = _errors(kb, article(body=GOOD_BODY.replace("When it applies.", "Cover it with cling film.")))
    assert "'plastic wrap'" in errs
    report, _ = kb(article(keywords=["diarrhoea", "nappies", "example"]))
    assert not any("British" in e for e in report.errors)


def test_long_steps_are_rejected_and_medium_ones_warned(kb):
    long_step = "1. " + " ".join(["word"] * 26) + "."
    errs = _errors(kb, article(body=GOOD_BODY.replace("1. Do this.", long_step)))
    assert "step 1 has 26 words" in errs and "split it into two" in errs
    medium = "1. " + " ".join(["word"] * 22) + "."
    report, _ = kb(article(body=GOOD_BODY.replace("1. Do this.", medium)))
    assert report.errors == [] and any("aim for 20" in w for w in report.warnings)


# --- pictures --------------------------------------------------------------------------------

def _png(path: Path, size=(8, 8)) -> Path:
    from PIL import Image
    Image.new("RGB", size, (200, 30, 30)).save(path)
    return path


@pytest.fixture
def kb_img(tmp_path: Path):
    """Like kb, plus an image registry. images: list of dicts written to images.yaml."""
    import yaml
    articles = tmp_path / "articles" / "first-aid"
    articles.mkdir(parents=True)
    (tmp_path / "images").mkdir()
    syn = tmp_path / "synonyms.yaml"
    syn.write_text("groups:\n  - [bleeding, blood]\n")

    def make(text: str, images: list[dict], files: dict[str, int] | None = None):
        (articles / "test-article.md").write_text(text)
        for name, kib in (files or {}).items():
            f = tmp_path / "images" / name
            if kib:
                f.write_bytes(b"0" * kib * 1024)
            else:
                _png(f)
        (tmp_path / "images.yaml").write_text(yaml.safe_dump({"images": images}))
        out = tmp_path / "kb.db"
        report = build_kb.build(out, articles_dir=tmp_path / "articles", synonyms_file=syn,
                                images_file=tmp_path / "images.yaml", images_dir=tmp_path / "images")
        return report, out
    return make


IMG = {"id": "test-pic", "file": "test-pic.png", "license": "public-domain", "creator": "US Army",
       "source": "https://example.org/fm", "ai_generated": False}
WITH_PIC = GOOD_BODY.replace("1. Do this.", "1. Do this.\n![Hands pressing on a wound with a cloth](image:test-pic)")


def test_picture_under_a_step_builds_into_database(kb_img):
    report, out = kb_img(article(body=WITH_PIC), [IMG], {"test-pic.png": 0})
    assert report.errors == []
    con = sqlite3.connect(out)
    mime, size, lic = con.execute("SELECT mime, length(data), license FROM images WHERE id='test-pic'").fetchone()
    assert mime == "image/png" and size > 0 and lic == "public-domain"
    # the picture's markup is not indexed as searchable text
    assert con.execute("SELECT count(*) FROM articles_fts WHERE articles_fts MATCH 'image'").fetchone() == (0,)


@pytest.mark.parametrize("change,expected", [
    ({"ai_generated": True}, "AI-generated pictures are not allowed"),
    ({"license": "unknown"}, "license must be one of"),
    ({"creator": ""}, "creator is empty"),
    ({"file": "missing.png"}, "does not exist"),
    ({"file": "test-pic.gif"}, "must be .webp"),
])
def test_picture_registry_rules(kb_img, change, expected):
    errs = " | ".join(kb_img(article(body=WITH_PIC), [{**IMG, **change}], {"test-pic.png": 0})[0].errors)
    assert expected in errs


def test_picture_missing_ai_generated_field_is_rejected(kb_img):
    img = {k: v for k, v in IMG.items() if k != "ai_generated"}
    errs = " | ".join(kb_img(article(body=WITH_PIC), [img], {"test-pic.png": 0})[0].errors)
    assert "missing 'ai_generated'" in errs


def test_oversized_picture_is_rejected(kb_img):
    errs = " | ".join(kb_img(article(body=WITH_PIC), [IMG], {"test-pic.png": 200})[0].errors)
    assert "the maximum is 150 KB" in errs


@pytest.mark.parametrize("body,expected", [
    (GOOD_BODY.replace("1. Do this.", "1. Do this.\n![pic](image:test-pic)"), "must say what it shows"),
    (GOOD_BODY.replace("1. Do this.", "1. Do this. ![A hand on a wound](image:test-pic)"), "on a line of its own"),
    (GOOD_BODY.replace("When it applies.", "When it applies.\n\n![A hand on a wound](image:test-pic)"),
     "only allowed in '## Steps'"),
    (GOOD_BODY.replace("1. Do this.", "![A hand on a wound](image:test-pic)\n1. Do this."), "directly under the step"),
    (GOOD_BODY.replace("1. Do this.", "1. Do this.\n![A hand on a wound](image:other-pic)"), "is not in"),
])
def test_picture_placement_and_description(kb_img, body, expected):
    errs = " | ".join(kb_img(article(body=body), [IMG], {"test-pic.png": 0})[0].errors)
    assert expected in errs


def test_unused_and_unlisted_pictures_are_rejected(kb_img):
    errs = " | ".join(kb_img(article(), [IMG], {"test-pic.png": 0, "stray.png": 0})[0].errors)
    assert "not used by any article" in errs and "not listed in" in errs


# --- codes, icons, rhythm ----------------------------------------------------------------------

@pytest.mark.parametrize("code,expected", [("fa-1", "is invalid"), ("WA-01", "does not match category")])
def test_code_rules(kb, code, expected):
    assert expected in _errors(kb, article(code=code))


def test_duplicate_codes_rejected(kb, tmp_path):
    (tmp_path / "articles" / "first-aid" / "other-article.md").write_text(
        article(id_="other-article").replace("title: Test article", "title: Other"))
    assert "already used by" in _errors(kb, article())


def test_unknown_icon_rejected(kb):
    assert "not in knowledge/icons.yaml" in _errors(kb, article(icon="unicorn"))


@pytest.mark.parametrize("rhythm,expected", [
    ({"bpm": 110, "from_step": 1}, "rhythm must be"),
    ({"bpm": 500, "from_step": 1, "to_step": 2}, "bpm must be 30-200"),
    ({"bpm": 110, "from_step": 1, "to_step": 9}, "only 2 steps"),
])
def test_rhythm_rules(kb, rhythm, expected):
    assert expected in _errors(kb, article(rhythm=rhythm))


def test_rhythm_and_icon_stored(kb):
    report, out = kb(article(icon="heart-pulse", rhythm={"bpm": 110, "from_step": 1, "to_step": 2}))
    assert report.errors == []
    row = sqlite3.connect(out).execute(
        "SELECT a.code, a.icon, a.rhythm_bpm, a.rhythm_from, a.rhythm_to, i.path IS NOT NULL, a.lead "
        "FROM articles a LEFT JOIN icons i ON i.name = a.icon").fetchone()
    assert row == ("FA-01", "heart-pulse", 110, 1, 2, 1, "something happened")


def test_new_article_assigns_next_code():
    from tools.new_article import next_code
    assert next_code("first-aid") == "FA-06"   # FA-01..FA-05 exist
    assert next_code("shelter") == "SH-01"     # no shelter articles yet
