# ADR-0091: Searching the help centre with Chroma

**Status:** Accepted
**Date:** 2026-11-23

## Context

ADR-0090 gave the traveller assistant a help centre: nine articles, every number in them checked against the code. The model cannot read all of it on every question (it would cost the free tier's tokens on every message and grow with every article), so it needs a tool that finds the few passages that answer a question: retrieval, the "R" of RAG. This slice builds that tool, `search_help_centre`, and measures it. The MCP server that offers it to a model is the next slice (ADR-0090's slice 3).

The author chose **Chroma** as the vector store.

Three facts found while building it shape the decision:

- **CrewAI does not install on Python 3.14.** Its newest release (1.15.44, 2026-11-19) says `requires-python <3.14`; on 3.14 pip falls back to a release from 2024 and fails to compile it. Chroma installs on either. Every other Python in SkyHub (the disruption agent, CI) is 3.14.
- **Chroma's default embedding model, all-MiniLM-L6-v2, reads at most 256 tokens** of a text and silently ignores the rest. A long section would be searched by its first part only.
- **Chroma sends anonymous usage telemetry by default.** It holds no question text, but SkyHub sends nothing to third parties that it does not need to.

## Options considered

Where the vectors live:

1. **pgvector in SkyHub's PostgreSQL.** Keeps all data in one kind of store, but the PostgreSQL image would change for every service, and a new database and migrations would be needed for a few dozen passages.
2. **Chroma, persisted to a folder built into the image.** Starts fastest, but the folder can be older than the articles beside it, and it has to be rebuilt by hand.
3. **Chroma in memory, built from the articles when the assistant starts.** Chosen. The index is always exactly the articles the ADR-0090 test checked, with no second copy to fall out of date. Embedding about fifty passages takes seconds.

How articles become passages:

1. **Fixed-size windows of words.** Cuts sentences and rules in half, and a window has no heading to say what it is about.
2. **One passage per article.** Too long for the model's 256 tokens, and a question about refunds would get the whole cancellations article.
3. **One passage per `##` section,** with the article title and the section heading in front of its text. Chosen: the articles are already written as short sections, each about one thing.

## Decision

- **The assistant moves to Python 3.13** (`requires-python = ">=3.12,<3.14"`, CI job on 3.13), so that CrewAI can be added in ADR-0090's slice 4 without changing the base again. The disruption agent stays on 3.14. When CrewAI supports 3.14, the assistant can move back.
- **Passages** (`traveller_assistant.passages`): one for the text before the first `##` heading (if any), and one for each `##` section. Each has an id (`checking-in-online#seats-after-check-in`), the article id, title and heading, and the text that is embedded: `"<title>. <heading>.\n<section text>"`. Two sections with the same heading in one article are refused, because their ids would collide.
- **A passage is at most 150 words** (`MAX_WORDS`), checked by a test over the real help centre, so it fits the model's 256 tokens with room to spare. A writer whose section grows past that splits it with a new heading; the test says which passage and how long.
- **Embeddings come through a small port, `Embedder`** (`embed(texts) -> vectors`). The real adapter is Chroma's own all-MiniLM-L6-v2 (local, free, no key; downloaded once, about 80 MB, and checked against a fixed SHA-256 by Chroma). Tests use a fake that needs no model, so the unit tests run offline in under a second. Vectors are passed to Chroma directly, so a different model is a different `Embedder`, with nothing else edited.
- **The index** (`HelpCentreIndex`): an in-memory Chroma collection with **cosine** distance, **telemetry off**, and **no embedding function of Chroma's own** (vectors come only from the `Embedder`; a test makes Chroma's model raise if anything calls it). `search(question, limit)` returns passages nearest first, **dropping any farther than `max_distance`**.
- **The cut-off is 0.70, chosen from the evals, and it only removes questions that have nothing to do with SkyHub.** Measured: the right passage for every answerable question was nearer than 0.64; every off-topic question ("write me a poem", "the capital of Australia") was farther than 0.78. 0.70 sits between the two with a margin each side. What the measurement also showed is that **no cut-off can drop a travel question the help centre does not cover.** "How many kilos of baggage can I bring?" (SkyHub has no baggage rules) is 0.65 from the booking article, "do I need a visa?" 0.53 from the connections article: as near as real answers. A cut-off low enough to drop them (0.50) loses a third of the real answers. So retrieval is tuned to keep answers, and deciding that near passages do not answer the question is the model's job, held by the next rule.
- **Every answer with passages carries the rule** in a `note`: answer only from these passages, name the article, and if they do not answer the question say you do not know. With nothing found, the `note` says to say so and never guess (ADR-0090's rule).
- **The tool** (`search_help_centre`): takes a question and how many passages to return (1 to 5, default 3), refuses an empty question or one over 500 characters, and returns plain data: each passage's article id, title, heading and text, and the `note` above. The work is bounded: one embedding of at most 500 characters and one nearest-neighbour query of at most 5 results.
- **Evals** (`evals/`, run by pytest beside the tests, as in the disruption agent) with the real model, which CI caches. Three groups of questions, written the way a traveller asks: **49 answerable**, each with the article (or articles) that answer it; **8 off topic**, which must get nothing; **8 not covered** (baggage, pets, wifi, meals, visas, weather, hotels, loyalty points), which are reported but not held here, because retrieval cannot drop them (above): the answer evals of ADR-0090's slice 4 must show the model saying it does not know. `python -m evals` prints every question with its rank and distance, and the totals at a range of cut-offs.
- **Measured at 0.70: hit@1 92%, hit@3 100%, off topic got nothing 100%, not covered got nothing 25%.** The test fails below hit@1 88% or hit@3 96%, or if any off-topic question gets a passage. The bars are a little under the measurement because the model's arithmetic can differ slightly between machines. Lowering the cut-off to 0.50 fails both hit tests (checked).
- **Two articles changed because the evals found real gaps, not to suit the evals:** the cabins sentence had no section of its own (it sat under "One booking for the whole trip"), so "is there business class?" could not find it; it is now a "Cabins" section and says "Business class", as travellers do. And "Being moved to another flight" was 161 words, over the limit, so it is split at "After you have been moved".

## Consequences

- Positive: the assistant answers from a few short, checked passages, each naming its article, so an answer can say where it came from.
- Positive: a question that has nothing to do with SkyHub gets no passages at all, which makes "I do not know" the easy path for the model.
- Positive: retrieval quality is a number that a change to an article or the model has to keep.
- Negative: **two Python versions in the repository** (3.14 for the disruption agent, 3.13 for the assistant) until CrewAI supports 3.14.
- Negative: **the index is built at every start.** At about fifty passages this is seconds. At 100x (thousands of articles) it would be minutes and the persisted option (2 above), built in the image and keyed by a hash of the articles, would be needed.
- Negative: **the first start downloads the model** (about 80 MB). CI caches it; the container image (a later slice) must bake it in so a start does not depend on a download.
- Negative: **the eval questions are written by the same person as the articles**, so they are kinder than real travellers' questions. Real questions, when the chat exists, should be added to them.
- Negative: **a travel question the help centre does not cover still gets passages** (six of the eight not-covered questions did). Whether the assistant then says "I do not know" depends on the model following the rule in the `note`, which is not measured until the crew exists (ADR-0090's slice 4). Until then this is the largest known risk of a wrong answer.
- Negative: the distance cut-off is one number for every question; a borderline question can be dropped or kept wrongly. The evals measure both sides.
- No metric, alert or runbook entry yet: nothing serves traffic. They arrive with the API.
- Follow-up work: the traveller MCP server that offers this tool (ADR-0090's slice 3).
