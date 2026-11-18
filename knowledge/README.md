# Knowledge base

**Adding or editing articles? Follow [AGENTS.md](../AGENTS.md) step by step** (it is written for AI agents, but it's the clearest guide for people too). Run `python -m tools.check` when you're done.

Articles live in `articles/<category>/<id>.md`. Each file is Markdown with a YAML front matter block. `tools/build_kb.py` checks every article and compiles them into `build/knowledge.db`, the SQLite file the app ships.

## Status of the current content

**Every article here is an unreviewed draft.** They were written to exercise the pipeline and search, from widely published first-aid and preparedness guidance, but no qualified person has checked them. Their sources are listed by title and publisher only and have not yet been checked against the originals (`checked: false`).

A release build (`--release`) refuses to include any article that isn't `status: reviewed`.

## Front matter

```yaml
id: hypothermia                 # must match the file name
title: Hypothermia
category: first-aid             # must match the folder name
status: draft                   # draft | reviewed
version: 1                      # bump on every content change
high_risk: true                 # gets a home-screen shortcut and stricter review
license: CC-BY-4.0              # provisional until the content license is decided
situations:                     # how people describe the problem; weighted heavily in search
  - someone is shivering and confused
keywords: [cold, exposure]      # extra search terms
diagrams_needed:                # diagrams still to be drawn
  - where to apply warmth
sources:
  - title: Hypothermia first aid
    publisher: Example Org
    url: null                   # optional
    checked: false              # true once someone has compared the article with this source
review:                         # required when status is reviewed
  reviewer: null                # name and qualification
  date: null                    # YYYY-MM-DD
  review_by: null               # YYYY-MM-DD; after this the app flags the article as overdue
```

## Body

The body must contain these sections, in any order:

- `## When to use`
- `## What you need`
- `## Steps` (a numbered list)
- `## Warnings`
- `## Common mistakes`
- `## Get help`

Inside the body, link to another article with `[link text](article:<id>)`, for example `[start CPR](article:cpr)`. The app opens the target in a sheet over the current article. The build fails if a link points to an article that doesn't exist, or anywhere other than another article.

## Synonyms

`synonyms.yaml` holds groups of words that mean the same thing to a person in trouble ("shivering", "freezing", "hypothermia"). A query that uses any word in a group also searches for the rest of the group. This is the cheapest way to make search understand everyday language. Add to it whenever the benchmark shows a miss.
