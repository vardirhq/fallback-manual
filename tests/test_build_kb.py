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


def test_release_with_nothing_reviewed_fails(kb):
    report, out = kb(article(), release=True)
    assert any("no article is reviewed yet" in e for e in report.errors)


def test_release_leaves_out_drafts_and_unlinks_them(kb, tmp_path, monkeypatch):
    reviewers = tmp_path / "reviewers.yaml"
    reviewers.write_text("reviewers:\n  - name: Dr. Example, MD\n")
    monkeypatch.setattr(build_kb, "REVIEWERS_FILE", reviewers)
    articles = tmp_path / "articles" / "first-aid"
    articles.mkdir(parents=True, exist_ok=True)
    (articles / "other.md").write_text(article(id_="other", code="FA-02").replace("Test article", "Other article"))
    linked = GOOD_BODY.replace("When it applies.", "When it applies. See [the other one](article:other).")
    report, out = kb(article(status="reviewed", reviewer="Dr. Example, MD", body=linked), release=True)
    assert report.errors == []
    assert any("left out of this release build" in w for w in report.warnings)
    con = sqlite3.connect(out)
    assert con.execute("SELECT id FROM articles").fetchall() == [("test-article",)]
    body = con.execute("SELECT body_md FROM articles").fetchone()[0]
    assert "See the other one." in body and "article:other" not in body
    assert con.execute("SELECT value FROM meta WHERE key = 'article_count'").fetchone() == ("1",)


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


TWO_SOURCES = [{"title": "A source", "publisher": "A publisher", "url": "https://example.org/a", "checked": False},
               {"title": "B source", "publisher": "B publisher", "url": "https://example.org/b", "checked": False}]


def test_emergency_is_stored_and_only_for_high_risk(kb):
    report, out = kb(article(high_risk=True, emergency=True, sources=TWO_SOURCES))
    assert report.errors == []
    assert sqlite3.connect(out).execute("SELECT emergency FROM articles").fetchall() == [(1,)]
    report, out = kb(article())  # left out means not on the home screen's list
    assert sqlite3.connect(out).execute("SELECT emergency FROM articles").fetchall() == [(0,)]
    assert "only for high_risk articles" in _errors(kb, article(emergency=True))
    assert "emergency must be true or false" in _errors(kb, article(high_risk=True, emergency="yes", sources=TWO_SOURCES))


def test_short_title_is_stored_and_checked(kb):
    report, out = kb(article(short_title="Test"))
    assert report.errors == []
    assert sqlite3.connect(out).execute("SELECT short_title FROM articles").fetchall() == [("Test",)]
    assert "at most 22 characters" in _errors(kb, article(short_title="A short title that is far too long"))
    assert "same as the title" in _errors(kb, article(short_title="Test article"))


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
    (GOOD_BODY.replace("Call someone.", "Call someone.\n\n![A hand on a wound](image:test-pic)"),
     "not allowed in '## Get help'"),
    (GOOD_BODY.replace("## Warnings\n- Careful.", "## Warnings\n![A hand on a wound](image:test-pic)\n- Careful."),
     "can't be the first thing in '## Warnings'"),
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
    import re
    from tools.new_article import ARTICLES, next_code
    used = [int(m.group(1)) for f in ARTICLES.glob("*/*.md")
            if (m := re.search(r"^code:\s*FA-(\d+)\s*$", f.read_text(encoding="utf-8"), re.M))]
    assert used, "the real articles include first-aid ones"
    assert next_code("first-aid") == f"FA-{max(used) + 1:02d}"   # one past the highest, never reused
    from tools.new_article import categories
    empty = next(c for c in categories() if not any((ARTICLES / c["id"]).glob("*.md")))
    assert next_code(empty["id"]) == f"{empty['code']}-01"   # a category with no articles starts at 01



@pytest.mark.parametrize("body", [
    # A recognition picture under a paragraph in When to use
    GOOD_BODY.replace("When it applies.", "When it applies.\n\n![A hand on a wound with a cloth](image:test-pic)"),
    # Under a bullet in Warnings, What you need and Common mistakes
    GOOD_BODY.replace("- Careful.", "- Careful.\n![A hand on a wound with a cloth](image:test-pic)"),
    GOOD_BODY.replace("- Things", "- Things\n![A hand on a wound with a cloth](image:test-pic)"),
    GOOD_BODY.replace("- Oops.", "- Oops.\n![A hand on a wound with a cloth](image:test-pic)"),
])
def test_pictures_allowed_under_bullets_and_paragraphs(kb_img, body):
    report, _ = kb_img(article(body=body), [IMG], {"test-pic.png": 0})
    assert report.errors == []


# --- translations --------------------------------------------------------------

NB_BODY = textwrap.dedent("""
    ## Når brukes dette
    Når det gjelder.

    ## Dette trenger du
    - Ting

    ## Trinn
    1. Gjør dette.
    2. Så dette.

    ## Advarsler
    - Forsiktig.

    ## Vanlige feil
    - Uff.

    ## Få hjelp
    Ring noen.
""")


def translation(translation_of=1, body=NB_BODY, **overrides):
    import yaml
    meta = {
        "id": "test-article", "lang": "nb", "translation_of": translation_of, "title": "Testartikkel",
        "status": "draft", "version": 1, "license": "CC-BY-4.0",
        "situations": ["noe skjedde nettopp", "noe annet skjedde"], "keywords": ["prøve", "eksempel", "test"],
        "sources": [{"title": "A source", "publisher": "A publisher", "url": "https://example.org/a", "checked": False}],
        "review": {"reviewer": None, "date": None, "review_by": None},
    }
    meta.update(overrides)
    return "---\n" + yaml.safe_dump(meta, sort_keys=False, allow_unicode=True) + "---\n" + body


@pytest.fixture
def kb_nb(kb, tmp_path):
    """Builds with the English test article plus a translation; returns (report, en db, nb db)."""
    def make(text: str, english_version: int = 1, release=False):
        (tmp_path / "articles" / "first-aid" / "test-article.nb.md").write_text(text)
        report, out = kb(article(version=english_version), release=release)
        return report, out, build_kb.db_path(out, "nb")
    return make


def test_translation_builds_into_its_own_database(kb_nb):
    report, en, nb = kb_nb(translation())
    assert report.errors == []
    assert sqlite3.connect(en).execute("SELECT title FROM articles").fetchall() == [("Test article",)]
    con = sqlite3.connect(nb)
    # Code and category come from the English article; text and headings are Norwegian.
    assert con.execute("SELECT title, code, category FROM articles").fetchall() == [("Testartikkel", "FA-01", "first-aid")]
    assert "## Trinn" in con.execute("SELECT body_md FROM articles").fetchone()[0]
    assert con.execute("SELECT value FROM meta WHERE key = 'lang'").fetchone() == ("nb",)
    assert con.execute("SELECT title FROM categories WHERE id = 'first-aid'").fetchone() == ("Førstehjelp",)
    assert con.execute("SELECT count(*) FROM articles_fts WHERE articles_fts MATCH 'skjedde'").fetchone() == (1,)


def test_outdated_translation_warns_and_falls_back_to_english(kb_nb):
    report, _, nb = kb_nb(translation(translation_of=1), english_version=2)
    assert report.errors == []
    assert any("outdated" in w and "translation_of: 2" in w for w in report.warnings)
    assert sqlite3.connect(nb).execute("SELECT title FROM articles").fetchall() == [("Test article",)]


def test_release_leaves_out_draft_translations_without_failing(kb, tmp_path, monkeypatch):
    reviewers = tmp_path / "reviewers.yaml"
    reviewers.write_text("reviewers:\n  - name: Dr. Example, MD\n    qualification: Physician\n")
    monkeypatch.setattr(build_kb, "REVIEWERS_FILE", reviewers)
    (tmp_path / "articles" / "first-aid" / "test-article.nb.md").write_text(translation())
    report, out = kb(article(status="reviewed", reviewer="Dr. Example, MD"), release=True)
    assert report.errors == []
    # The draft translation is not shipped: Norwegian readers get the reviewed English article.
    nb = sqlite3.connect(build_kb.db_path(out, "nb"))
    assert nb.execute("SELECT title, status FROM articles").fetchall() == [("Test article", "reviewed")]


@pytest.mark.parametrize("text, expected", [
    (translation(code="FA-09"), "remove 'code'"),
    (translation(high_risk=True), "remove 'high_risk'"),
    (translation(emergency=True), "remove 'emergency'"),
    (translation(lang="en"), "lang must be nb"),
    (translation(translation_of="one"), "translation_of must be the version number"),
    (translation(translation_of=5), "only at version 1"),
    (translation(body=GOOD_BODY), "'## Når brukes dette'"),
    (translation(body=NB_BODY.replace("2. Så dette.\n", "")), "needs at least 2 numbered steps"),
    (translation(body=NB_BODY.replace("2. Så dette.", "2. Så dette.\n3. Og dette.")), "has 3 steps, but test-article.md has 2"),
    (translation(body=NB_BODY.replace("- Forsiktig.", "- Forsiktig.\n- Mer.")), "has 2 warnings"),
    (translation(body=NB_BODY + "\nSe [noe](article:missing)."), "must point to an existing article"),
])
def test_translation_rules(kb_nb, text, expected):
    report, _, _ = kb_nb(text)
    assert any(expected in e for e in report.errors), report.errors


def test_translation_without_english_article_is_rejected(kb, tmp_path):
    (tmp_path / "articles" / "first-aid" / "other.nb.md").write_text(translation(id="other"))
    report, _ = kb(article())
    assert any("no English article" in e for e in report.errors)


def test_unknown_language_is_rejected(kb, tmp_path):
    (tmp_path / "articles" / "first-aid" / "test-article.xx.md").write_text(translation())
    report, _ = kb(article())
    assert any("'xx' is not a language" in e for e in report.errors)


def test_translations_skip_the_american_english_rule(kb_nb):
    report, _, _ = kb_nb(translation(body=NB_BODY.replace("Ring noen.", "Ring noen. Colour centre.")))
    assert report.errors == []


def test_vocab_knows_the_parts_of_hyphenated_words(kb):
    # Search splits "hjerte-lunge-redning" into three words; if "lunge" were unknown, spelling
    # correction would turn it into some other known word.
    report, out = kb(article(keywords=["hjerte-lunge-redning", "example", "sample"]))
    assert report.errors == []
    words = {w for (w,) in sqlite3.connect(out).execute("SELECT word FROM vocab")}
    assert {"hjerte-lunge-redning", "hjerte", "lunge", "redning"} <= words
