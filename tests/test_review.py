"""The review pack (tools/review_pack.py) and applying sign-offs (tools/apply_review.py)."""

import json
import re
import shutil
from pathlib import Path

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
    # Published as a website, so every page is marked as a draft and kept out of search engines.
    for name in ("index.html", "cpr.html", "cpr.nb.html"):
        html = (out / name).read_text()
        assert 'name="robots" content="noindex' in html and "Draft for expert review" in html


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


def issue_body(s: dict) -> Path:
    """A sign-off as the review site puts it into a GitHub issue."""
    return ("Sent from the Fallback Manual review site. Please do not edit the block below.\r\n\r\n"
            "```yaml\r\n" + yaml.safe_dump(s, sort_keys=False, allow_unicode=True).replace("\n", "\r\n") + "```\r\n")


@pytest.fixture
def github_reviewer(manual, tmp_path):
    (tmp_path / "reviewers.yaml").write_text(f'reviewers:\n  - name: "{REVIEWER}"\n    github: "@DrExample"\n')


def test_signoff_sent_from_the_reviewers_github_account(manual, github_reviewer, tmp_path):
    body = tmp_path / "issue.txt"
    body.write_text(issue_body(signoff()))
    result = tmp_path / "result.json"
    assert apply_review.main(["--github-user", "drexample", "--result", str(result), str(body)]) == 0
    assert "status: reviewed" in (manual / "cpr.md").read_text()
    out = json.loads(result.read_text())
    assert out["outcome"] == "applied" and out["article"] == "cpr" and out["file"].endswith("cpr.md")


@pytest.mark.parametrize("user, expected", [
    ("someone-else", "not theirs"),
])
def test_signoff_from_another_github_account_is_refused(manual, github_reviewer, tmp_path, user, expected):
    body = tmp_path / "issue.txt"
    body.write_text(issue_body(signoff()))
    result = tmp_path / "result.json"
    assert apply_review.main(["--github-user", user, "--result", str(result), str(body)]) == 1
    out = json.loads(result.read_text())
    assert out["outcome"] == "refused" and expected in out["message"]
    assert "status: draft" in (manual / "cpr.md").read_text()


def test_reviewer_without_a_github_username_cant_sign_off_from_github(manual, tmp_path):
    body = tmp_path / "issue.txt"
    body.write_text(issue_body(signoff()))
    result = tmp_path / "result.json"
    assert apply_review.main(["--github-user", "drexample", "--result", str(result), str(body)]) == 1
    assert "no GitHub username" in json.loads(result.read_text())["message"]


def test_changes_requested_from_github(manual, github_reviewer, tmp_path):
    body = tmp_path / "issue.txt"
    body.write_text(issue_body(signoff(decision="changes", comments="Step 3 is wrong.")))
    result = tmp_path / "result.json"
    assert apply_review.main(["--github-user", "DrExample", "--result", str(result), str(body)]) == 1
    out = json.loads(result.read_text())
    assert out["outcome"] == "changes" and "Step 3 is wrong." in out["message"]


def test_text_without_a_signoff(tmp_path, capsys):
    body = tmp_path / "issue.txt"
    body.write_text("I think the CPR article is great!")
    assert apply_review.main([str(body)]) == 1
    assert "no sign-off found" in capsys.readouterr().err


def test_page_script_has_no_line_breaks_inside_strings():
    # A "\n" written without escaping in the Python source becomes a real line break inside a
    # JavaScript string, which breaks every button on the page.
    for n, line in enumerate(review_pack.SCRIPT.splitlines(), 1):
        code = re.sub(r"\\.", "", line)
        assert code.count("'") % 2 == 0, f"line {n} of the page script has an unclosed string: {line}"


def test_signoff_relayed_by_a_maintainer_from_an_email(manual, tmp_path):
    # The reviewer has no GitHub account: they emailed the sign-off, and the owner pasted the email
    # (quoted, with a signature) into an issue.
    s = yaml.safe_dump(signoff(comments="Looks good.\nOne thing: step 3."), sort_keys=False, allow_unicode=True)
    email = ("Hi,\n\nHere is my review.\n\n" + "".join("> " + l + "\n" for l in s.splitlines())
             + "\nBest regards,\nDr. Example\nSent from my phone\n")
    body = tmp_path / "issue.txt"
    body.write_text(email)
    result = tmp_path / "result.json"
    assert apply_review.main(["--relayed-by", "owner", "--result", str(result), str(body)]) == 0
    out = json.loads(result.read_text())
    assert out["outcome"] == "applied" and out["relayed_by"] == "owner"
    assert "status: reviewed" in (manual / "cpr.md").read_text()


def test_relaying_still_needs_a_registered_reviewer(manual, tmp_path):
    body = tmp_path / "issue.txt"
    body.write_text(issue_body(signoff(reviewer="Someone Unknown")))
    result = tmp_path / "result.json"
    assert apply_review.main(["--relayed-by", "owner", "--result", str(result), str(body)]) == 1
    assert "not in knowledge/reviewers.yaml" in json.loads(result.read_text())["message"]


def test_email_button_only_with_a_review_address(tmp_path, monkeypatch):
    review_pack.build_pack(tmp_path / "without", only={"cpr"})
    assert "sendEmail()\">" not in (tmp_path / "without" / "cpr.html").read_text()
    monkeypatch.setattr(review_pack, "REVIEW_EMAIL", "reviews@example.org")
    review_pack.build_pack(tmp_path / "with", only={"cpr"})
    page = (tmp_path / "with" / "cpr.html").read_text()
    assert "Send review by email" in page and "reviews@example.org" in page
