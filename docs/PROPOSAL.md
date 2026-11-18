# Fallback Manual
## Project Proposal and Technical Vision — Revision 2

**Project type:** Open-source, offline-first Android application
**Core technology:** Curated reference library, local search, optional compact retrieval model
**Target size:** Lite build ≤ 50 MB · Full build ≤ 200 MB (hard ceiling 300 MB)
**Primary platform:** Android
**Retrieval model working name:** Nano
**Status:** Concept and feasibility planning

---

## What changed in Revision 2

| Area | Revision 1 | Revision 2 |
|---|---|---|
| Role of the model | Interprets questions *and explains* retrieved material | **Finds** the right reviewed article; the app shows reviewed text verbatim |
| Model strategy | Three equal approaches, including training from scratch | Start from existing small models or embeddings; from-scratch training removed from the roadmap |
| Operating modes | Emergency, Assisted, Detailed | **Browse** (no AI) and **Ask** (AI-assisted lookup) |
| Storage budget | 40 MB text, no diagrams, single build | ~10 MB text, explicit diagram budget, **Lite** and **Full** builds |
| Content | One section among many | A first-class workstream with licensing and clinical sign-off from Phase 0 |
| Distribution | Install from a store | Plus **offline peer-to-peer sharing** of the APK |
| Baseline | Search vs. AI prototype | Model must beat FTS5 + synonyms + small embeddings to ship |

---

## 1. Executive Summary

**Fallback Manual is a compact, fully offline emergency and survival reference for Android, built to stay useful when the internet, electricity and normal information services are gone.**

The product is a **curated, reviewed and cited reference manual**. Everything else serves that manual. It opens instantly, works with no network, and runs on cheap and old phones.

An optional compact model, **Nano**, lets users describe a problem in their own words ("my friend is shivering and confused") and takes them to the right reviewed procedure. Nano does not write instructions. The text the user acts on is always the reviewed article, word for word.

### Guiding principle

> Knowledge when everything else fails.

The goal is not the smartest AI. It is the most dependable way to get the right instructions in front of someone under stress.

---

## 2. Problem Statement

People now solve unfamiliar problems with search engines, videos and cloud AI. Those disappear during:

- Extended power outages
- Natural disasters
- Telecommunications failures
- Remote wilderness emergencies
- Infrastructure disruption, armed conflict and displacement
- Restricted or censored internet access

The phone often keeps working long after the network fails, but most apps don't.

Offline manuals solve part of the problem, but users must know what to look for and how to read the terminology. Cloud assistants are easy to talk to but useless offline, and confidently wrong answers are dangerous in an emergency.

Fallback Manual pairs the reliability of a reviewed printed manual with a lookup that understands everyday language.

### Existing alternatives and our position

| Alternative | Strength | Gap |
|---|---|---|
| Kiwix (WikiMed, Wikipedia) | Huge offline coverage | Not curated for emergencies; long encyclopedic articles; unreviewed for action under stress |
| Commercial survival-guide apps | Polished content | Often closed, paid, unversioned, uncited |
| Printed field manuals | Fully reliable | Heavy, hard to search, not with you |
| Cloud AI assistants | Natural language | Need a network; can hallucinate |

**Our edge: curated, reviewed, cited, versioned, open and tiny.**

---

## 3. Project Objectives

### Primary objectives

1. **Complete offline operation**, including first launch. No internet permission in the standard builds.
2. **Small footprint.** Lite build ≤ 50 MB; Full build ≤ 200 MB, never above 300 MB.
3. **Broad device compatibility.** ARM64 Android phones with 4 GB RAM; CPU only.
4. **Reviewed practical knowledge.** Every procedure has a source, a reviewer and a review date.
5. **Natural-language lookup**, optional and never required.
6. **Energy efficiency.** Browsing costs almost nothing; AI lookup runs only on request.
7. **Transparency.** Every answer shows which article it came from and its version.
8. **Open source**, with separate, clearly stated licenses for code, model weights and content.
9. **Offline spread.** One phone can pass the app to another without a network.

### Secondary objectives

- Norwegian as a second language, tested early (see §6.5).
- Preparedness planning: checklists, inventories and household profiles.
- Bookmarks and a pinned "my critical procedures" list.
- Replaceable, signed knowledge packs in later versions.

---

## 4. Product Philosophy

Fallback Manual should feel like emergency equipment, not a chatbot.

- **Reliability over creativity.** Nothing the user acts on is generated.
- **Browsing over conversation.** Under stress, people tap "Bleeding" rather than type a sentence. The category screen is the main interface.
- **Accessibility over sophistication.** Works on modest hardware with no setup.
- **Efficiency over features.** Every component must justify its storage, memory and energy cost.
- **Knowledge over AI.** The library is the product. Nano is a way into it.

---

## 5. Core Functionality

### 5.1 Reference library

Initial categories:

| Category | Example topics |
|---|---|
| First aid | Bleeding, burns, fractures, hypothermia, recovery position, CPR |
| Water | Purification, filtration, storage, contamination |
| Fire and warmth | Fire starting, insulation, safe indoor heating, carbon monoxide |
| Shelter | Emergency shelters, weather protection |
| Food | Storage without refrigeration, spoilage, rationing |
| Navigation and signaling | Maps, compass, orientation, signaling for rescue |
| Electricity | Batteries, solar charging, power conservation |
| Sanitation | Hygiene, waste disposal, infection prevention |
| Repairs | Basic tools, improvisation, troubleshooting |
| Preparedness | Supplies, planning, communication |

Every article uses the same structure:

- Title, category and stable article ID
- When to use it (situations and symptoms)
- What you need
- Numbered steps, **each with a diagram where it helps**
- Warnings and when *not* to do this
- Common mistakes
- When to get professional help
- Sources
- Reviewer, review date and content version

Diagrams are required, not decoration, for tourniquets, the recovery position, CPR hand placement, splints, knots, shelters and water filters.

### 5.2 Ask: natural-language lookup

Users can type questions such as:

- "How can I make river water safer to drink?"
- "How do I stay warm when the power is out?"
- "Someone is shivering and confused, what do I do?"
- "How do I signal for help with no phone reception?"

The system **matches the question to one or more reviewed articles and opens them**. It can add one short, templated line of context ("This may be hypothermia."), but the steps shown are always the reviewed text.

If no article matches with enough confidence, the app says so plainly and offers the nearest categories. It never makes up an answer.

### 5.3 Operating modes

**Browse (default, no AI)**
Category tiles, an emergency quick-access screen, keyword search, bookmarks. Loads instantly and must work even if the model fails to load or is not installed.

**Ask (AI-assisted lookup)**
Natural-language question → ranked reviewed articles. Available in the Full build or when the Lite build finds search alone is enough (see §6).

There is no separate "detailed" mode that generates longer answers. A longer generated answer is not a more accurate one.

### 5.4 Offline sharing

Fallback Manual is most useful to the people who didn't install it beforehand. The app will include **"Share this app"**, which sends the APK and the current knowledge pack to a nearby phone using Android's built-in offline transfer (Nearby Share / Quick Share, Bluetooth, or Wi-Fi Direct), with an on-screen guide for enabling installs from unknown sources.

The shared package must be signed, so the receiver can tell it hasn't been tampered with.

---

## 6. Nano: The Retrieval Model

### 6.1 Role

Nano's job is narrow:

- Understand everyday phrasing and symptoms
- Map the question to the right article(s) and category
- Spot when nothing in the library fits
- Ask one clarifying question when the answer depends on it (for example adult vs. child)

Nano **does not**:

- Write or rephrase procedures
- Add facts from its own parameters
- Answer questions the library does not cover

### 6.2 Build it in layers and stop as soon as it's good enough

Each layer must beat the one before on the benchmark in §13 before the next one is added.

| Layer | What it is | Approx. size | Ships in |
|---|---|---:|---|
| 0 | SQLite FTS5 + hand-built synonym and symptom lists ("shivering" → hypothermia) | < 1 MB | Lite and Full |
| 1 | Small sentence-embedding model for semantic search, e.g. a quantized MiniLM-class model | 20–30 MB | Lite (if it earns its space) and Full |
| 2 | Small existing language model used as a re-ranker / intent classifier / clarifying-question selector, e.g. SmolLM2-135M or -360M, Gemma 3 270M | 100–250 MB (4-bit) | Full only |

Candidate models are examples, to be re-checked for license and quality during Phase 0.

### 6.3 Model strategy

**Primary: use and fine-tune existing models.**
Fine-tune or distill an existing small model on question-to-article matching examples. This reuses real language understanding and keeps cost low.

**Removed from the roadmap: training from scratch.**
A from-scratch model at 50–250M parameters on a project budget is very unlikely to beat existing open models of the same size, and it would cost more than the rest of the project combined. It may be revisited as separate research only if Layers 0–2 all fail for a clearly identified reason.

### 6.4 Why the model never writes the steps

A model this small makes mistakes when rephrasing, and a rephrased step in a first-aid procedure (a dosage, a time, a "do not") can cause harm. Restricting Nano to *selecting* reviewed text turns its mistakes into "showed the wrong article," which the user can see and recover from, instead of "gave a wrong instruction," which they can't.

### 6.5 Norwegian

Small models and small embedding models are noticeably weaker in Norwegian than in English. Phase 0 will run the benchmark in Norwegian too. If quality is not acceptable, Norwegian ships with Browse plus FTS5 and synonym lists only, and that is fine.

### 6.6 Inspiration

The project was partly inspired by TINY 2.1, an experimental transformer small enough to fit on a floppy disk (https://github.com/WollyMonjac/Tiny-2.1). It shows how small transformers can be. Fallback Manual takes the lesson about compactness but relies on proven existing models for the actual product.

---

## 7. Technical Architecture

### 7.1 High-level design

```text
                     Android UI
                         |
          +--------------+--------------+
          |                             |
       Browse                          Ask
  (categories, quick-              (question text)
   access, bookmarks)                    |
          |                  Layer 0: FTS5 + synonyms
          |                  Layer 1: embeddings (opt.)
          |                  Layer 2: Nano re-rank (opt.)
          |                             |
          |                    Confidence check ---- too low --> "No reviewed
          |                             |                         article found"
          +--------------+--------------+                         + nearest categories
                         |
               Reviewed Knowledge Base
             (SQLite: articles, diagrams,
              sources, reviewer, version)
                         |
             Article shown verbatim, with
             source and version visible
```

### 7.2 Proposed technology stack

| Component | Candidate technology |
|---|---|
| Android app | Kotlin, Jetpack Compose |
| Knowledge storage | SQLite |
| Keyword retrieval | SQLite FTS5 |
| Semantic retrieval | ONNX Runtime Mobile or llama.cpp embeddings |
| Re-ranker / classifier | llama.cpp (GGUF) |
| Diagrams | SVG / Android vector drawables (small, sharp at any size) |
| Content authoring | Markdown + YAML front matter, compiled to SQLite at build time |
| Training and evaluation | Python, PyTorch |
| Build and release | Gradle, GitHub Actions, reproducible builds |
| Distribution | F-Droid, GitHub Releases, Google Play, offline sharing |

No custom inference runtime unless existing ones demonstrably can't meet the constraints.

### 7.3 Safety architecture

Safety does not rely on a model prompt:

- Only reviewed articles are ever shown as instructions.
- The model's output is a list of article IDs plus a confidence score, not free text.
- Below a confidence threshold, the app says it found no match.
- High-risk topics (bleeding, CPR, poisoning, hypothermia, carbon monoxide) have fixed shortcuts from the home screen, independent of search.
- Every article shows its source, reviewer, review date and version.
- Clear "call emergency services if you can" guidance where relevant.
- Knowledge packs carry dates, so outdated guidance is visible as outdated.

The app is not a substitute for emergency services, medical professionals or training, and says so.

---

## 8. Storage and Resource Constraints

### 8.1 Two builds

| Component | Lite | Full |
|---|---:|---:|
| App, UI, SQLite, runtime | 15 MB | 20 MB |
| Article text (English) | 5 MB | 5 MB |
| Diagrams (vector, some raster) | 10 MB | 10 MB |
| Search index, synonyms, metadata | 3 MB | 3 MB |
| Embedding model (if it earns its place) | 0–25 MB | 25 MB |
| Nano re-ranker (4-bit) | — | 100–125 MB |
| Reserve | 5 MB | 12 MB |
| **Total** | **≤ 50 MB** | **≤ 200 MB** |

Notes:

- Reviewed text is small. Even 1,000 thorough articles is a few megabytes. The real size costs are diagrams and the model.
- Both builds work fully offline from first launch. Lite is the default for offline sharing because it transfers quickly over Bluetooth.
- On Google Play, the Full build may need Play Asset Delivery for the model file; F-Droid and GitHub Releases ship the APK directly.
- All figures are provisional and must be measured on real builds continuously.

### 8.2 Hardware targets

- ARM64 Android phone, 4 GB RAM
- CPU only; no NPU or GPU required
- A 135–360M model quantized to 4 bits needs a few hundred MB of RAM, which fits comfortably

### 8.3 Battery

- No background work of any kind
- Model loads only when Ask is used, and unloads afterwards
- Re-ranking produces a few tokens, not long answers
- Browse needs no model at all
- A setting turns AI off completely

Power use is measured, not just speed.

---

## 9. Content and Review

The content is the product and the hardest part of the project. It has its own workstream from Phase 0.

### 9.1 Sources and licensing

| Source type | Examples | License position |
|---|---|---|
| US government publications | Army field manuals, CDC, FEMA/Ready.gov | Generally public domain; good base |
| Other governments | Norwegian DSB (Direktoratet for samfunnssikkerhet og beredskap) preparedness material | Check each source's terms |
| NGOs | Red Cross, St John Ambulance | Copyrighted; needs permission |
| Wikipedia / WikiMed | Broad coverage | CC BY-SA: share-alike affects the content bundle's license |
| Original writing | Written by contributors from cited sources | Our choice of license (e.g. CC BY 4.0) |

Every article records where it came from, and the build fails if an article has no source or license.

### 9.2 Review process

- **Two-tier review:** a content editor checks clarity and structure; a qualified reviewer signs off on anything medical, chemical, electrical or food-safety related.
- Medical content needs named clinician reviewers (doctor, nurse, paramedic or wilderness-medicine instructor) before any production release.
- Reviews are recorded in the article metadata and in version control.
- Articles have a review-by date; overdue articles are flagged in the app.

### 9.3 Liability

The project will settle its disclaimer wording, contributor terms and reviewer arrangements before the first public release, ideally with legal input. An open-source license does not remove these concerns.

### 9.4 Diagrams

Diagrams are budgeted, commissioned or drawn, licensed and reviewed like text. Vector formats keep them small and readable at any size.

---

## 10. User Experience

The app should look like a modern field manual: calm, practical, high-contrast.

### Visual direction

- Muted forest greens and earth tones, with a clear red reserved for emergencies
- High-contrast type, large touch targets
- No decorative animation
- Dark mode by default at night to save battery on OLED screens

### Screens

**Home:** big emergency tiles (Bleeding, Not breathing, Burns, Cold, Water…), a search/ask field, and pinned procedures.
**Manual:** browse by category.
**Article:** numbered steps with diagrams, warnings, source and version.
**Ask:** question field and ranked matching articles (Full build, or Lite if search is enough).
**Preparedness:** checklists, inventories, optional household profile.
**Share:** send the app to a nearby phone.
**Settings:** AI on/off, language, storage, content version, battery options.

### Accessibility

Scalable text, screen-reader support, strong contrast, simple navigation, usable with one hand and in low light.

---

## 11. Preparedness Features

An optional, on-device module for use before an emergency:

- Household size, region and climate
- Supplies, water storage, cooking and heating equipment, backup power
- Care and accessibility needs

It produces checklists and highlights missing supplies. It is never required, and nothing leaves the device.

---

## 12. Privacy and Security

- No accounts, ads, analytics, cloud inference or remote activation
- No internet permission in the standard builds
- Questions are never stored or transmitted
- Signed APKs and signed knowledge packs; signature checked on offline-shared packages
- Optional future update mechanism ships as a separate, clearly labeled build or module
- Preparedness data is stored locally and can be wiped in one tap

---

## 13. Evaluation and Success Metrics

### 13.1 Technical

| Metric | Target |
|---|---|
| Lite build size | ≤ 50 MB |
| Full build size | ≤ 200 MB (≤ 300 MB hard limit) |
| Device | 4 GB RAM, ARM64, CPU only |
| Cold start to Home | < 2 s on the reference device |
| Ask latency | < 3 s on the reference device |
| Internet requirement | None |

### 13.2 Retrieval benchmark

A public benchmark of real-sounding questions, written by people who did not write the articles, including:

- Plain and paraphrased questions
- Symptom descriptions without the medical term
- Misspellings and stressed, fragmentary typing
- Questions with **no** answer in the library
- English and Norwegian

Measures:

- Correct article in the top 1 and top 3
- Correct "no match" rate on unanswerable questions
- Wrong confident matches on high-risk topics (target: as close to zero as possible)
- Latency, RAM and energy per query

**Ship rule:** a layer (embeddings, Nano) is only included if it clearly beats the layer below it on this benchmark and does not increase wrong confident matches on high-risk topics.

### 13.3 Human evaluation

- Timed tasks with non-technical users: "find what to do for X" under time pressure
- Compare Browse-only against Ask
- Clinical reviewers audit high-risk articles and the matches that lead to them

---

## 14. Development Roadmap

### Phase 0: Feasibility, content and baseline

- Build a 30–50 article pilot library from public-domain sources, with diagrams
- Recruit at least one clinical reviewer
- Settle the content license and contributor terms
- Prototype SQLite FTS5 + synonym search
- Write the first version of the retrieval benchmark
- Measure embedding models and small LLM re-rankers against the baseline, in English and Norwegian
- Measure size, RAM, latency and power on a real 4 GB device

**Deliverable:** feasibility report and benchmark results.
**Exit:** the pilot library is useful offline with search alone, and the size budgets look achievable.

### Phase 1: Offline manual (MVP)

- Android app with Home, Manual, Article, Bookmarks and Settings
- Content pipeline: Markdown + YAML → SQLite at build time, failing on missing sources or reviews
- FTS5 search with synonyms
- Emergency quick-access tiles
- Offline APK sharing
- Lite build

**Deliverable:** a fully offline, searchable, reviewed survival manual. No AI.

### Phase 2: Retrieval research

- Add embeddings if they beat the baseline
- Fine-tune an existing small model as a re-ranker / classifier
- Test unanswerable and misleading questions
- Decide per language what ships

**Deliverable:** benchmarked retrieval layers, or a documented decision to stay with search.

### Phase 3: Ask integration

- On-demand model loading and unloading
- Ask screen with ranked articles and confidence handling
- Clarifying questions where useful
- Full build

**Deliverable:** Full build meeting size, hardware and benchmark targets.

### Phase 4: Content and safety expansion

- Grow the library category by category, with review
- Decision trees for the highest-risk scenarios
- Realistic scenario testing and usability studies

### Phase 5: Public release 1.0

- Final licenses for code, weights and content
- Reproducible builds and published benchmarks
- Model provenance and limitations documented
- F-Droid, GitHub Releases and Play listings
- Maintenance and review schedule

Release follows demonstrated quality, not a date.

---

## 15. Major Risks

| Risk | Mitigation |
|---|---|
| Content is wrong, outdated or unsafe | Sources required, clinical sign-off, review dates, visible versions |
| Not enough qualified reviewers | Start recruiting in Phase 0; ship a smaller library rather than unreviewed content |
| Licensing conflicts (e.g. CC BY-SA) | Decide the content license in Phase 0; track license per article |
| Liability | Clear disclaimers, contributor terms, legal input before release |
| Model picks the wrong article | Model only selects; confidence threshold; high-risk shortcuts bypass search |
| Model adds little over search | Ship rule in §13.2; Lite build works without it |
| Weak Norwegian support | Test in Phase 0; fall back to search-only for Norwegian |
| Device fragmentation | Test on real low-end devices; Browse needs no model |
| People don't install it in advance | Offline sharing; tiny Lite build |
| Maintenance burden | Review-by dates, small core library, community contributions with review |

---

## 16. Open-Source Strategy

```text
fallback-manual/
├── android/      # Android app
├── knowledge/    # Articles (Markdown + YAML), diagrams, sources
├── retrieval/    # FTS5 setup, synonyms, embedding and re-rank code
├── nano/         # Fine-tuning and evaluation of the retrieval model
├── evaluation/   # Benchmarks and safety tests
├── tools/        # Content compiler and validators
├── docs/         # Proposal, architecture, content guide
└── README.md
```

Licenses are tracked separately for:

- App and tooling code (e.g. Apache-2.0 or GPL-3.0)
- Model weights (inherit the base model's license)
- Training data
- Article content and diagrams (e.g. CC BY 4.0, or CC BY-SA if Wikipedia material is used)

---

## 17. Minimum Viable Product

1. Native Android app, fully offline from first launch
2. 30–50 reviewed, cited articles with diagrams, covering the highest-risk topics first
3. Emergency quick-access tiles
4. Category browsing
5. FTS5 search with synonyms and symptom terms
6. Bookmarks and pinned procedures
7. Offline sharing of the app
8. Lite build ≤ 50 MB
9. A published benchmark comparing search alone with at least one model-assisted layer

**No AI is required for the MVP.**

---

## 18. Final Vision

Fallback Manual is a small app, installed once or passed from phone to phone, holding carefully reviewed practical knowledge and an optional local model that helps people find the right page fast.

No account. No cloud. No subscription. No connection required.

**Fallback Manual**
*Knowledge when everything else fails.*
