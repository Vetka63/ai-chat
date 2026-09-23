"""Детерминированный HTTP mock API вымышленных игр для задания Дня 17."""

import json
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


@app.get("/health")
def health() -> dict[str, str]:
    """Подтверждает готовность отдельного Docker-сервиса."""

    return {"status": "ok"}


@app.get("/games", response_model=GamesResult)
def search_games(query: str = Query(min_length=1, max_length=100)) -> GamesResult:
    """Находит игры по словам в названии или описании без обращения к LLM."""

    words = query.casefold().split()
    if not words:
        raise HTTPException(status_code=422, detail="Укажите название или тему игры")
    matches = [game for game in GAMES if all(word in f"{game.title} {game.description}".casefold() for word in words)]
    return GamesResult(query=query, games=matches)
