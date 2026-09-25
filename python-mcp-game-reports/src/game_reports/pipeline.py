"""Реализации трёх независимых этапов MCP-композиции."""

import asyncio
import os
import re
from pathlib import Path
from uuid import UUID, uuid4

import httpx
from pydantic import ValidationError

from game_reports.models import SearchResult, SummaryResult


class PipelineError(Exception):
    """Безопасная для пользователя ошибка одного из этапов."""


class GameCatalog:
    """Получает игры только из доверенного учебного HTTP API."""

    def __init__(self, base_url: str, http: httpx.AsyncClient | None = None):
        self.base_url = base_url
        self.http = http

    @staticmethod
    def search_term(query: str) -> str:
        """Убирает вводные слова из темы, сохраняя название или ключевые слова."""
        leading = {
            "игра", "игры", "игру", "играх", "играм", "игр", "про", "об", "о",
            "на", "по", "тему", "теме", "отчёт", "отчет", "сводка", "сводку",
            "каталог", "каталоге", "в", "нашем",
        }
        words = query.split()
        while len(words) > 1 and words[0].casefold().strip("«»\"'.,:;") in leading:
            words.pop(0)
        return " ".join(words)

    async def search(self, query: str) -> SearchResult:
        term = self.search_term(query)
        try:
            if self.http is None:
                async with httpx.AsyncClient(base_url=self.base_url, timeout=5) as client:
                    response = await client.get("/games", params={"query": term})
            else:
                response = await self.http.get("/games", params={"query": term})
            response.raise_for_status()
            return SearchResult.model_validate(response.json())
        except (httpx.HTTPError, ValueError, ValidationError) as exc:
            raise PipelineError("Не удалось получить данные игрового каталога") from exc


def summarize_games(search: SearchResult) -> SummaryResult:
    """Составляет воспроизводимый Markdown исключительно по найденным играм."""

    lines = [f"# Игры по запросу «{search.query}»", "", f"Найдено игр: {len(search.games)}.", ""]
    if not search.games:
        lines.append("В учебном каталоге подходящих игр не найдено.")
    for game in search.games:
        title = re.sub(r"\s+", " ", game.title).strip()
        description = re.sub(r"\s+", " ", game.description).strip()
        lines.extend([f"## {title}", "", description, ""])
    return SummaryResult(query=search.query, game_count=len(search.games),
                         markdown="\n".join(lines).rstrip() + "\n")


class FileReportStore:
    """Сохраняет отчёты под серверным UUID, не принимая путь от модели."""

    def __init__(self, directory: Path):
        self.directory = directory
        self._lock = asyncio.Lock()

    async def save(self, summary: SummaryResult, operation_id: str | None) -> tuple[str, str]:
        try:
            report_id = str(UUID(operation_id)) if operation_id else str(uuid4())
        except ValueError as exc:
            raise PipelineError("Некорректный идентификатор операции") from exc
        file_name = f"report-{report_id}.md"
        async with self._lock:
            try:
                self.directory.mkdir(parents=True, exist_ok=True)
                destination = self.directory / file_name
                if destination.exists():
                    if destination.read_text(encoding="utf-8") != summary.markdown:
                        raise PipelineError("Операция уже сохранила другой отчёт")
                    return report_id, file_name
                temporary = self.directory / f".{file_name}.{uuid4()}.tmp"
                try:
                    temporary.write_text(summary.markdown, encoding="utf-8")
                    os.replace(temporary, destination)
                finally:
                    temporary.unlink(missing_ok=True)
                return report_id, file_name
            except OSError as exc:
                raise PipelineError("Не удалось сохранить отчёт") from exc
