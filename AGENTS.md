# Instructions for AI agents

This repository is **Fallback Manual**: an offline emergency and survival manual for Android. People will read these articles in real emergencies, so mistakes can hurt someone. Follow these instructions exactly.

Most tasks are **adding or editing articles**. Articles are Markdown files in `knowledge/articles/<category>/<id>.md`. The app is built from them automatically. You never need to touch the app's code.

You are done only when this command prints `RESULT: PASS`:

```sh
python -m tools.check
```

If it prints `RESULT: FAIL`, it lists every problem with a "Fix:". Do what it says, run it again, and repeat until it passes. Never say the task is finished while it fails.

---

## Rules you must never break

1. **Never set `status: reviewed`.** New and edited articles are always `status: draft`. Only a human expert reviews articles.
2. **Never fill in `reviewer`, `date` or `review_by`.** Leave all three as `null`.
3. **Never set `checked: true` on a source.** Always `checked: false`.
4. **Never edit `knowledge/reviewers.yaml`.**
5. **Never invent facts.** Every instruction, number, time, temperature, distance and amount must come from one of the sources you list. If the sources don't say it, leave it out.
6. **Never copy text word for word** from a source. Write it in your own plain words. (Sources are often copyrighted; the articles are CC-BY-4.0.)
7. **Never change a question to make a check pass**, and never add or change questions with `set: heldout`. Only add `set: dev` questions.
8. **Only edit these files:**
   - `knowledge/articles/**/*.md` (articles)
   - `evaluation/questions.yaml` (only add `set: dev` lines)
   - `knowledge/synonyms.yaml` (only if a check tells you to)

   Do not edit anything in `android/`, `tools/`, `retrieval/`, `tests/`, `.github/`, or `knowledge/categories.yaml`, unless the person you work for explicitly asks.

---

## Setup (once)

```sh
pip install -r requirements-dev.txt
```

---

## Task: add a new article

### Step 1. See what exists

```sh
python -m tools.new_article --list
```

This shows the categories and every existing article. If an article on the topic already exists, **edit that one instead** (see "Task: edit an article").

### Step 2. Create the file

```sh
python -m tools.new_article --category <category> --id <id> --title "<Title>"
```

- `<category>`: one id from the list in step 1, e.g. `water`.
- `<id>`: lowercase words joined by hyphens, e.g. `water-filtration`. It becomes the file name.
- `<Title>`: short, no full stop at the end, e.g. `"Filtering water"`.
- Add `--high-risk` if a mistake could seriously injure or kill someone (first aid, poisoning, electricity, fire, cold, drowning, and so on). High-risk articles need sources from **at least 2 different publishers**.

Example:

```sh
python -m tools.new_article --category water --id water-filtration --title "Filtering water"
```

This creates `knowledge/articles/water/water-filtration.md` with every part in place and `TODO` where you must write.

### Step 3. Research

Use only these kinds of sources:

- World Health Organization (WHO)
- National public health bodies: CDC (US), NHS (UK), Helsedirektoratet / Helsenorge (Norway), and similar
- Red Cross / Red Crescent (IFRC and national societies), St John Ambulance
- Resuscitation councils: European Resuscitation Council, American Heart Association
- National civil protection agencies: FEMA / Ready.gov (US), DSB (Norway), and similar
- Official military field manuals, e.g. US Army FM 21-76

Never use: blogs, forums, Reddit, YouTube, news articles, product sites, Wikipedia, or other AI tools.

If two good sources disagree, follow the **more cautious** one, and mention the risk under `## Warnings`.

### Step 4. Fill in the header

Replace every `TODO` between the two `---` lines:

| Field | What to write |
|---|---|
| `situations` | 2–5 sentences describing the problem **the way a frightened person would say it**, e.g. `my child swallowed something and is coughing`. Search relies heavily on these. |
| `keywords` | 3–12 lowercase words or short phrases people might type, e.g. `[choking, food stuck, can't breathe, coughing]`. |
| `diagrams_needed` | Pictures that would make the steps clearer, e.g. `where to place your hands`. Remove the field if none. |
| `sources` | One entry per source: `title` (the page title), `publisher` (the organisation), `url` (the full `https://` address, with no `?utm_source=...`), and `checked: false`. |

Leave `status: draft`, `version: 1`, `license: CC-BY-4.0` and the `review` block exactly as they are.

### Step 5. Write the body

The body has **exactly these six sections, in this order**, each starting with `## `:

1. `## When to use`: one or two short paragraphs: the situation and the signs to look for.
2. `## What you need`: a bullet list. Every line starts with `- `. If nothing is needed: `- Nothing but your hands`.
3. `## Steps`: a numbered list: `1. `, `2. `, `3. ` ... with no gaps. Nothing else in this section.
4. `## Warnings`: a bullet list (`- `) of dangers and things not to do.
5. `## Common mistakes`: a bullet list (`- `).
6. `## Get help`: a short paragraph on when and how to get professional help.

Writing rules:

- Short sentences. Plain words. Write for someone who is scared and reading on a phone.
- Steps are instructions: start with a verb ("Press...", "Call...", "Move...").
- One action per step.
- Give measurements in metric first, then imperial in brackets: `5–6 cm (about 2 inches)`.
- You may use `**bold**` for a few critical words.
- To refer to another article, link it: `[start CPR](article:cpr)`. Use only ids from `python -m tools.new_article --list`.

Not allowed (the check rejects them): other headings (`#`, `###`), tables, images, code blocks, HTML, indented or nested lists, `*` or `•` bullets, citation markers like `[1]` or `【...】` or `cite...`, and any leftover `TODO`.

Look at `knowledge/articles/water/boiling-water.md` for a complete, correct example.

### Step 6. Add search questions

Open `evaluation/questions.yaml`. Find the comment line `# --- not in the library` and add **at least 2** lines just above it, in this exact format:

```yaml
  # --- water-filtration ---
  - {set: dev, q: "how do I filter muddy water", expect: [water-filtration]}
  - {set: dev, q: "cloth filter for river water", expect: [water-filtration]}
```

Write the questions the way a real person would type them on a phone, not with the article's own title.

### Step 7. Run the check

```sh
python -m tools.check
```

Fix every problem it lists, then run it again. Repeat until it prints `RESULT: PASS`.

If it says a question doesn't find your article: add the missing words to the article's `situations` or `keywords`. Do **not** change the question.

---

## Task: edit an existing article

1. Open the file in `knowledge/articles/<category>/<id>.md`.
2. Make the change. Every new fact must come from a source in its `sources` list; add the source if it isn't there.
3. Increase `version` by 1 (e.g. `version: 2` becomes `version: 3`).
4. If the article was `status: reviewed`, change it to `status: draft` and set `reviewer`, `date` and `review_by` to `null`, and every source's `checked` to `false`. Changed content must be reviewed again.
5. Run `python -m tools.check` until it prints `RESULT: PASS`.

---

## When you finish

Tell the person you work for:

- which articles you added or changed,
- which sources you used,
- anything you were unsure about, or left out because the sources didn't cover it.

If you commit, use a message like: `Add draft article: Filtering water`.
