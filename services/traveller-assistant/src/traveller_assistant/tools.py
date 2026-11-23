"""The tools the assistant's model can call (ADR-0091). Plain functions over plain data: the MCP server of the next
slice offers them to a model without changing them."""

from __future__ import annotations

from traveller_assistant.index import HelpCentreIndex

MAX_QUESTION_CHARACTERS = 500
MAX_PASSAGES = 5
DEFAULT_PASSAGES = 3

NOTHING_FOUND = ("The help centre has nothing that answers this. Tell the traveller you do not know, suggest the help "
                 "centre or the Manage booking page, and do not guess.")

# Passages near a question do not always answer it: "how much baggage can I bring?" finds the booking article, which
# says nothing about baggage (ADR-0091). So every answer with passages carries the rule.
ONLY_FROM_THESE = ("Answer only from these passages and name the article you used. If they do not answer the question, "
                   "tell the traveller you do not know and do not guess.")


class ToolInputError(ValueError):
    """A call the tool refuses; the message is written for the model, so it can correct the call."""


def search_help_centre(index: HelpCentreIndex, question: str, limit: int = DEFAULT_PASSAGES) -> dict:
    """Passages of the help centre that answer ``question``, each naming the article it comes from.

    Bounded work whatever the caller sends: one embedding of at most 500 characters, one query of at most 5 results.
    """
    question = question.strip()
    if not question:
        raise ToolInputError("Ask a question: the question was empty.")
    if len(question) > MAX_QUESTION_CHARACTERS:
        raise ToolInputError(f"Ask a shorter question: at most {MAX_QUESTION_CHARACTERS} characters.")
    if not 1 <= limit <= MAX_PASSAGES:
        raise ToolInputError(f"Ask for between 1 and {MAX_PASSAGES} passages.")

    passages = [{"article": f.article_id, "title": f.title, "section": f.heading, "text": f.text}
                for f in index.search(question, limit)]
    return {"passages": passages, "note": ONLY_FROM_THESE if passages else NOTHING_FOUND}
