from pathlib import Path

import pytest

from evaluation.run_benchmark import run, summarise
from retrieval.search import Searcher, normalise
from tools import build_kb


@pytest.fixture(scope="session")
def db(tmp_path_factory) -> Path:
    out = tmp_path_factory.mktemp("kb") / "knowledge.db"
    report = build_kb.build(out)
    assert report.errors == []
    return out


@pytest.fixture(scope="session")
def searcher(db):
    s = Searcher(db)
    yield s
    s.close()


def test_normalise():
    assert normalise("Someone’s BLEEDING!! won't stop") == ["someone", "bleeding", "won", "stop"]


@pytest.mark.parametrize("query,expected", [
    ("someone is shivering and confused", "hypothermia"),
    ("blood everywhere", "severe-bleeding"),
    ("make river water drinkable", "boiling-water"),
    ("no reception, need rescue", "signaling-for-help"),
])
def test_finds_expected_article(searcher, query, expected):
    r = searcher.search(query)
    assert r.confident
    assert r.best.id == expected


def test_corrects_typos(searcher):
    r = searcher.search("bleding")
    assert r.corrections == {"bleding": "bleeding"}
    assert r.best.id == "severe-bleeding"


def test_multiword_synonym_is_one_concept(searcher):
    concepts, _ = searcher.concepts("the power is out")
    assert len(concepts) == 1 and concepts[0].from_synonyms


@pytest.mark.parametrize("query", ["how do I fix a flat bike tire", "snake bite", "", "how do I"])
def test_says_no_match_when_nothing_fits(searcher, query):
    assert not searcher.search(query).confident


def test_benchmark_has_no_dangerous_answers(db):
    """Guard rail: a change that makes search confidently wrong on high-risk topics, or
    confident about questions the library cannot answer, must not be merged."""
    m = summarise(run(db, 0.6, None)["rows"])
    assert m["dangerous"] == 0
    assert m["no_match_correct"] == 100
