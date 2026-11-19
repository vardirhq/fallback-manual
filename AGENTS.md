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
4. **Never edit `knowledge/reviewers.yaml`, and never change an article's `code`** (e.g. `FA-02`). `python -m tools.new_article` assigns codes; they never change.
5. **Never add, remove or change `emergency: true`** unless the person you work for asks. It puts an article on the home screen's short list of act-now topics and opens it with "Call emergency services first". A person curates that list.
6. **Never invent facts.** Every instruction, number, time, temperature, distance and amount must come from one of the sources you list. If the sources don't say it, leave it out, even if it seems like good advice.
7. **Never copy text word for word** from a source. Write it in your own plain words. (Sources are often copyrighted; the articles are CC-BY-4.0.)
8. **Never change a question to make a check pass**, and never add or change questions with `set: heldout`. Only add `set: dev` questions.
9. **Only edit these files:**
   - `knowledge/articles/**/*.md` (articles)
   - `evaluation/questions.yaml` (only add `set: dev` lines)
   - `knowledge/synonyms.yaml` and `knowledge/synonyms.nb.yaml` (only if a check tells you to)
   - `knowledge/images/` and `knowledge/images.yaml`, **only through** `python -m tools.add_image` (see "Adding a picture")

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
- `<Title>`: **the name of the problem**, short, no full stop at the end, like the existing titles: `"Dehydration"`, `"Burns and scalds"`, `"Hypothermia"`, `"Filtering water"`. Not `"Recognizing and responding to dehydration"` and not `"How to ..."`.
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

Every source must be **mainly about this article's topic**. A page about one disease is not a source for general first aid, even if it mentions the topic.

**Never add a source just to reach the two-publisher minimum** for high-risk articles. If you can't find two good sources that are mainly about the topic, stop and tell the person you work for.

If two good sources disagree, follow the **more cautious** one, and mention the risk under `## Warnings`.

### Step 4. Fill in the header

Replace every `TODO` between the two `---` lines:

| Field | What to write |
|---|---|
| `situations` | 2–5 sentences describing the problem **the way a frightened person would say it**, e.g. `my child swallowed something and is coughing`. Search relies heavily on these. |
| `keywords` | 3–12 lowercase words or short phrases people might type, e.g. `[choking, food stuck, can't breathe, coughing]`. |
| `diagrams_needed` | Pictures that would make the steps clearer, e.g. `where to place your hands`. Remove the field if none. |
| `icon` (optional) | One icon name from `python -m tools.new_article --list`, e.g. `icon: snowflake`. Leave it out to use the category's icon. |
| `sources` | One entry per source: `title` (the page title), `publisher` (the organisation), `url` (the full `https://` address, with no `?utm_source=...`), and `checked: false`. |

Leave `code`, `status: draft`, `version: 1`, `license: CC-BY-4.0` and the `review` block exactly as they are.

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
- One action per step. **Aim for 20 words or fewer per step; more than 25 is rejected.** If a step is too long, split it into two steps.
- **American English** in titles and article text of English articles (translations: see "Task: translate an article"): color, center, liter, meter, diarrhea, recognize, signaling, gray, diapers (not nappies), plastic wrap (not cling film), soda (not fizzy drinks). The check rejects common British spellings and words. (In `keywords` and `situations`, British spellings are fine, because people type both.)
- Give measurements in metric first, then imperial in brackets: `5–6 cm (about 2 inches)`.
- You may use `**bold**` for a few critical words.
- To refer to another article, link it: `[start CPR](article:cpr)`. Use only ids from `python -m tools.new_article --list`.

Not allowed (the check rejects them): other headings (`#`, `###`), tables, code blocks, HTML, indented or nested lists, `*` or `•` bullets, citation markers like `[1]` or `【...】` or `cite...`, and any leftover `TODO`. Pictures are allowed only as described in "Adding a picture".

Look at `knowledge/articles/water/boiling-water.md` for a complete, correct example.

### Step 6. Add search questions

Open `evaluation/questions.yaml`. Find the comment line `# --- not in the library` and add **at least 2** lines just above it, in this exact format:

```yaml
  # --- water-filtration ---
  - {set: dev, q: "how do I filter muddy water", expect: [water-filtration]}
  - {set: dev, q: "cloth filter for river water", expect: [water-filtration]}
```

Write the questions the way a real person would type them on a phone, not with the article's own title.

### Step 7. Check every sentence against your sources

Read the article from top to bottom. For each sentence, find the source that says it. If none of your sources says it, delete the sentence. No script can do this for you.

### Step 8. Run the check

```sh
python -m tools.check
```

Fix every problem it lists, then run it again. Repeat until it prints `RESULT: PASS`.

If it says a question doesn't find your article: add the missing words to the article's `situations` or `keywords`. Do **not** change the question.

---

## Adding a picture

Pictures help with things that are hard to describe, such as hand positions or where to tie a tourniquet. A wrong picture is worse than none, so the rules are strict.

**Never use a picture made by an AI image generator.** They often get hands, bodies and positions wrong.

**Only use a picture if you can confirm its license.** Allowed licenses: `public-domain`, `CC0-1.0`, `CC-BY-4.0`, `CC-BY-SA-4.0`. Good places to look:

- US government works, which are public domain: CDC Public Health Image Library (phil.cdc.gov), US Army field manuals, FEMA
- Wikimedia Commons, but only files whose page states one of the licenses above

If you can't find a suitable picture with a confirmed license, don't add one. List it under `diagrams_needed` instead, so a person can find or draw it.

### Step 1. Add the file

```sh
python -m tools.add_image --id <image-id> --file <path-to-picture> \
    --license <license> --creator "<who made it>" --source "<URL, plus page or figure number>" --not-ai-generated
```

This converts the picture to the right format and size, and records its license, creator and source in `knowledge/images.yaml`. Never edit those files by hand.

### Step 2. Put it in the article

Put it on its own line **directly under the thing it shows**:

- a step in `## Steps`, for how to do something;
- a bullet in `## What you need`, `## Warnings` or `## Common mistakes`;
- a paragraph in `## When to use`, for a picture that helps people recognize the problem.

A picture can't be the first thing in a section, and pictures are not allowed in `## Get help`. Only pictures under steps appear in step-by-step mode.

```markdown
5. Put the heel of one hand on the center of the chest.
![Heel of one hand on the center of the chest, other hand on top](image:cpr-hand-position)
6. Lock your fingers together.
```

```markdown
- Place generators outdoors at least 6 m (20 ft) away from doors, windows and vents.
![A generator outside, well away from the house's windows and doors](image:generator-distance)
```

The text in the square brackets describes what the picture shows, in 10–200 characters. People using a screen reader hear only this, so describe the picture, not just "diagram".

Then remove that picture from the article's `diagrams_needed` list, and run `python -m tools.check`.

---

## Task: edit an existing article

1. Open the file in `knowledge/articles/<category>/<id>.md`.
2. Make the change. Every new fact must come from a source in its `sources` list; add the source if it isn't there.
3. Increase `version` by 1 (e.g. `version: 2` becomes `version: 3`).
4. If the article was `status: reviewed`, change it to `status: draft` and set `reviewer`, `date` and `review_by` to `null`, and every source's `checked` to `false`. Changed content must be reviewed again.
5. Run `python -m tools.check` until it prints `RESULT: PASS`.

Translations of the article (`<id>.nb.md` and so on) now show a warning that they are outdated, and the app shows the English article instead until they are updated. If you can, update each translation as in "Task: translate an article"; otherwise tell the person you work for.

---

## Task: translate an article

Translations are separate files next to the English article. Today the only other language is Norwegian Bokmål (`nb`). The English article is always the original; a translation says the same thing in another language, nothing more and nothing less.

1. Copy `knowledge/articles/<category>/<id>.md` to `knowledge/articles/<category>/<id>.nb.md` (or open the existing translation).
2. Make the header exactly these fields, in this order. Remove every other field (`category`, `code`, `high_risk`, `emergency`, `icon`, `rhythm`, `diagrams_needed`): the translation takes them from the English article.

   ```yaml
   id: <same id as the English article>
   lang: nb
   translation_of: <the English article's version number>
   title: <the title, translated>
   status: draft
   version: <1 for a new translation; increase by 1 when you change it>
   license: CC-BY-4.0
   situations: <rewritten as a frightened person in that language would say it>
   keywords: <the words people in that language would type>
   sources: <copied unchanged from the English article>
   review: <reviewer, date and review_by all null>
   ```

3. Use these six headings, in this order: `## Når brukes dette`, `## Dette trenger du`, `## Trinn`, `## Advarsler`, `## Vanlige feil`, `## Få hjelp`.
4. Translate every sentence. Keep **the same number of steps and the same number of warnings**, in the same order. Do not merge, split, add or drop any. The check rejects a translation whose counts differ.
5. Keep every link (`article:<id>`, same id) and every picture (`image:<id>`, same id). Translate only the picture's description.
6. Add nothing that is not in the English article, even if it is good advice. Give metric units only (drop the imperial ones in brackets). The American English rule does not apply to translations.
7. In `evaluation/questions.yaml`, add at least 2 questions in that language just above `# --- not in the library`, with `lang: nb`:

   ```yaml
     - {set: dev, lang: nb, q: "han skjelver og snakker rart", expect: [hypothermia]}
   ```

8. Run `python -m tools.check` until it prints `RESULT: PASS`. If a Norwegian question doesn't find the article, add words to the translation's `situations` or `keywords`, or a group to `knowledge/synonyms.nb.yaml`.

A translation, like any article, stays `status: draft` until a human expert who reads the language reviews it.

---

## When you finish

Tell the person you work for:

- which articles you added or changed,
- which sources you used,
- anything you were unsure about, or left out because the sources didn't cover it.

If you commit, use a message like: `Add draft article: Filtering water`.
