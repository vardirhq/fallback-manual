"""The review pack (tools/review_pack.py) and applying sign-offs (tools/apply_review.py)."""

import shutil

import pytest
import yaml

from tools import apply_review, build_kb, review_pack

REVIEWER = "Dr. Example, MD"


@pytest.fixture
def manual(tmp_path, monkeypatch):
    """A copy of two real articles (CPR, English and Norwegian, and choking) and a reviewer list."""
    src = build_kb.ARTICLES_DIR / "first-aid"
    dst = tmp_path / "articles" / "first-aid"
    dst.mkdir(parents=True)
    for name in ("cpr.md", "cpr.nb.md", "choking.md", "cpr-children.md", "unconscious-breathing.md"):
        shutil.copy(src / name, dst / name)
    reviewers = tmp_path / "reviewers.yaml"
    reviewers.write_text(f'reviewers:\n  - name: "{REVIEWER}"\n')
    monkeypatch.setattr(build_kb, "ARTICLES_DIR", tmp_path / "articles")
    monkeypatch.setattr(build_kb, "REVIEWERS_FILE", reviewers)
    return dst


def signoff(aid="cpr", lang="en", **changes):
    article = apply_review.find_article(aid, lang)
    s = {"fallback-manual-review": 1, "article": aid, "lang": lang, "version": article.meta["version"],
         "fingerprint": review_pack.fingerprint(article), "reviewer": REVIEWER, "decision": "approve",
         "date": "2026-11-19", "review_by": "2027-11-19",
         "sources_confirmed": list(range(len(article.meta["sources"]))), "comments": "(none)"}
    s.update(changes)
    return s


def write(tmp_path, s, name="review.yaml"):
    p = tmp_path / name
    p.write_text(yaml.safe_dump(s, allow_unicode=True))
    return p


def test_pack_has_a_page_per_article_and_language(tmp_path):
    out = tmp_path / "pack"
    assert review_pack.build_pack(out, only={"cpr"}) == 0
    assert {p.name for p in out.iterdir()} == {"index.html", "cpr.html", "cpr.nb.html"}
    page = (out / "cpr.nb.html").read_text()
    assert "open the English article" in page and 'href="cpr.html"' in page
    assert "Hjerte-lunge-redning" in page
    # Works offline: no scripts, styles, fonts or pictures fetched from anywhere else.
    assert "<script src" not in page and "<link" not in page and 'src="http' not in page


def test_approved_signoff_marks_the_article_reviewed(manual, tmp_path):
    assert apply_review.main([str(write(tmp_path, signoff()))]) == 0
    text = (manual / "cpr.md").read_text()
    assert "status: reviewed" in text
    assert f'reviewer: "{REVIEWER}"' in text and "date: 2026-11-19" in text and "review_by: 2027-11-19" in text
    assert "checked: false" not in text
    # The result is a valid reviewed article.
    report = build_kb.Report()
    build_kb.load_articles(build_kb.ARTICLES_DIR, report, release=True)
    assert not [e for e in report.errors if "cpr.md" in e]


def test_translation_signoff(manual, tmp_path):
    assert apply_review.main([str(write(tmp_path, signoff(lang="nb")))]) == 0
    assert "status: reviewed" in (manual / "cpr.nb.md").read_text()
    assert "status: draft" in (manual / "cpr.md").read_text()  # the English article is untouched


def test_dry_run_changes_nothing(manual, tmp_path):
    before = (manual / "cpr.md").read_text()
    assert apply_review.main(["--dry-run", str(write(tmp_path, signoff()))]) == 0
    assert (manual / "cpr.md").read_text() == before


@pytest.mark.parametrize("changes, expected", [
    ({"reviewer": "Someone Else"}, "not in knowledge/reviewers.yaml"),
    ({"fingerprint": "0000000000000000"}, "has changed since the reviewer read it"),
    ({"version": 1}, "has changed since the reviewer read it"),
    ({"sources_confirmed": [0]}, "did not confirm every source"),
    ({"decision": "changes", "comments": "Step 3 is wrong."}, "Step 3 is wrong."),
    ({"review_by": "2026-01-01"}, "review_by must be after"),
    ({"fallback-manual-review": 2}, "not a Fallback Manual review sign-off"),
])
def test_signoffs_that_are_refused(manual, tmp_path, capsys, changes, expected):
    before = (manual / "cpr.md").read_text()
    assert apply_review.main([str(write(tmp_path, signoff(**changes)))]) == 1
    assert expected in capsys.readouterr().err
    assert (manual / "cpr.md").read_text() == before


def test_editing_the_article_invalidates_an_old_signoff(manual, tmp_path, capsys):
    s = signoff()
    path = manual / "cpr.md"
    old = path.read_text()
    new = old.replace("## Steps\n\n1. ", "## Steps\n\n1. First, ", 1)
    assert new != old
    path.write_text(new)
    assert apply_review.main([str(write(tmp_path, s))]) == 1
    assert "has changed" in capsys.readouterr().err
