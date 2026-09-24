"""Детерминированный HTTP mock API вымышленных игр для задания Дня 17."""

import json
import re
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel


class Game(BaseModel):
    """Публичные данные игры: только название и краткое описание."""

    title: str
    description: str


class GamesResult(BaseModel):
    """Результат поиска, включая исходный запрос для проверяемости."""

    query: str
    games: list[Game]


GAMES = [Game.model_validate(item) for item in json.loads(Path(__file__).with_name("games.json").read_text(encoding="utf-8"))]
app = FastAPI(title="Games Mock API", version="1.0.0")


def _words(value: str) -> list[str]:
    """Разбивает текст на слова и убирает различие между «е» и «ё»."""

    return re.findall(r"[^\W_]+", value.casefold().replace("ё", "е"))


def _matches_word(query_word: str, catalog_word: str) -> bool:
    """Допускает простое изменение окончания длинного слова, не применяя LLM."""

    if query_word == catalog_word:
        return True
    if len(query_word) < 5 or len(catalog_word) < 5:
        return False
    return query_word[:min(len(query_word), len(catalog_word)) - 1] == catalog_word[:min(len(query_word), len(catalog_word)) - 1]


@app.get("/health")
def health() -> dict[str, str]:
    """Подтверждает готовность отдельного Docker-сервиса."""

    return {"status": "ok"}


@app.get("/games", response_model=GamesResult)
def search_games(query: str = Query(min_length=1, max_length=100)) -> GamesResult:
    """Находит игры по словам в названии или описании без обращения к LLM."""

    words = _words(query)
    if not words:
        raise HTTPException(status_code=422, detail="Укажите название или тему игры")
    matches = [game for game in GAMES if all(
        any(_matches_word(word, candidate) for candidate in _words(f"{game.title} {game.description}"))
        for word in words
    )]
    return GamesResult(query=query, games=matches)
