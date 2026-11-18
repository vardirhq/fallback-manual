# Fallback Manual

*Knowledge when everything else fails.*

An open-source, fully offline emergency and survival reference for Android. A curated, reviewed and cited manual first, with an optional compact model ("Nano") that helps people find the right procedure in everyday language. Nano never writes the instructions; it only points to reviewed articles.

**Status:** Phase 0. Content pipeline, Layer 0 search and benchmark are working. No Android app yet. **All articles are unreviewed drafts. Do not rely on them.**

- [Project proposal (Revision 2)](docs/PROPOSAL.md)
- [Article format and content status](knowledge/README.md)

## Layout

```text
knowledge/    articles (Markdown + YAML) and synonym groups
tools/        build_kb.py: validates articles and compiles build/knowledge.db
retrieval/    search.py: Layer 0 search (FTS5 + synonyms + typo fixes + confidence)
evaluation/   benchmark questions and runner
tests/        pytest suite
```

## Quick start

Requires Python 3.11+.

```sh
pip install -r requirements-dev.txt

python -m tools.build_kb                      # build build/knowledge.db (drafts allowed)
python -m tools.build_kb --release            # fails unless every article is reviewed

python -m retrieval.search "someone is shivering and confused"
python -m evaluation.run_benchmark --failures
python -m pytest -q
```

## Current benchmark (Layer 0 only)

8 draft articles, 52 questions, threshold 0.6:

| Set | Questions | Top-1 | Top-3 | Correct "no match" | Dangerous answers |
|---|---:|---:|---:|---:|---:|
| dev (tuned on) | 24 | 100% | 100% | 100% | 0 |
| held-out | 28 | 95% | 100% | 100% | 0 |

These numbers flatter the system: the library is tiny and the questions were written by the same author as the articles. They become meaningful once the library grows and outsiders write questions.
