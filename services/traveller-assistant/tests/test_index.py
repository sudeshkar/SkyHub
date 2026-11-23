from traveller_assistant.help_centre import Article
from traveller_assistant.index import HelpCentreIndex, chroma_settings
from traveller_assistant.passages import passages_of
from tests.fakes import WordsEmbedder

ARTICLES = [
    Article("checking-in-online", "Checking in online", "s", (),
            "## When you can check in\n\nOnline check-in opens before departure.\n\n"
            "## Boarding passes\n\nA boarding pass has a QR code.\n"),
    Article("cancellations-and-refunds", "Cancellations and refunds", "s", (),
            "## Do I get my money back\n\nA refund is given when you cancel early.\n"),
]


def index(max_distance=0.9, embedder=None):
    return HelpCentreIndex([p for a in ARTICLES for p in passages_of(a)], embedder or WordsEmbedder(),
                           max_distance=max_distance)


def test_finds_the_passage_that_answers_the_question_first_with_its_article_and_heading():
    found = index().search("when does online check-in open", limit=3)

    assert (found[0].article_id, found[0].heading) == ("checking-in-online", "When you can check in")
    assert found[0].text == "Online check-in opens before departure."


def test_returns_passages_nearest_first_and_no_more_than_asked_for():
    found = index(max_distance=2.0).search("refund money cancel", limit=2)

    assert len(found) == 2
    assert found[0].article_id == "cancellations-and-refunds"
    assert found[0].distance <= found[1].distance


def test_a_question_the_help_centre_does_not_answer_gets_nothing_rather_than_the_least_bad_passage():
    assert index().search("recipe for chocolate cake", limit=3) == []


def test_embeds_every_passage_once_when_built_and_the_question_once_when_searched():
    embedder = WordsEmbedder()
    built = index(embedder=embedder)
    built.search("boarding pass", limit=3)

    assert [len(call) for call in embedder.calls] == [3, 1]


def test_two_indexes_in_one_process_never_see_each_others_passages():
    only_refunds = HelpCentreIndex(passages_of(ARTICLES[1]), WordsEmbedder(), max_distance=2.0)
    index()

    assert {p.article_id for p in only_refunds.search("check-in boarding pass", limit=5)} == {"cancellations-and-refunds"}


def test_sends_no_usage_telemetry():
    assert chroma_settings().anonymized_telemetry is False


def test_only_the_embedder_turns_text_into_vectors_so_chroma_never_runs_a_model_of_its_own(monkeypatch):
    from chromadb.utils import embedding_functions

    def refuse(*args, **kwargs):
        raise AssertionError("Chroma's own model was used")

    monkeypatch.setattr(embedding_functions.DefaultEmbeddingFunction, "__init__", refuse)
    monkeypatch.setattr(embedding_functions.DefaultEmbeddingFunction, "__call__", refuse)

    assert index().search("when does online check-in open", limit=1)[0].article_id == "checking-in-online"


def test_measures_cosine_distance():
    assert index()._collection._model.configuration_json["hnsw"]["space"] == "cosine"


def test_says_how_many_passages_it_holds():
    assert len(index()) == 3
