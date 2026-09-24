"""Контракт учебного каталога игр."""

from fastapi.testclient import TestClient

from app import app


def test_search_finds_title_and_description():
    api = TestClient(app)
    assert api.get("/games", params={"query": "Лунный архив"}).json()["games"][0]["title"] == "Лунный архив"
    assert any(item["title"] == "Звёздные тропы" for item in api.get("/games", params={"query": "космос"}).json()["games"])


def test_search_handles_basic_word_forms_and_punctuation():
    api = TestClient(app)
    for query in ("погода", "погодой", "дождь", "дождя?", "Механика дождя"):
        titles = [item["title"] for item in api.get("/games", params={"query": query}).json()["games"]]
        assert "Механика дождя" in titles, query


def test_unknown_game_returns_empty_list():
    result = TestClient(app).get("/games", params={"query": "несуществующая игра"})
    assert result.status_code == 200
    assert result.json() == {"query": "несуществующая игра", "games": []}


def test_empty_query_is_rejected():
    assert TestClient(app).get("/games", params={"query": ""}).status_code == 422
    assert TestClient(app).get("/games", params={"query": "   "}).status_code == 422
