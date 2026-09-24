"""Подстраховка выбора каталога, когда модель признала нехватку знаний."""

import re
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class LookupRetry:
    """Причина и разрешённые инструменты для единственной повторной попытки."""

    reason: str
    tools: list[dict[str, Any]]


class GameCatalogLookupPolicy:
    """Не даёт агенту закончить ответом «не знаю», не проверив доступный каталог.

    Правило относится только к поиску игр и живёт рядом с игровым агентом.
    Оно не включает отключённые серверы и не подменяет каталог веб-поиском.
    """

    _game_title = re.compile(r"\b(?:игр(?:а|е|у|ы|ой)|game)\s+[«\"'“]?[^\W_]", re.IGNORECASE)
    _named_subject = re.compile(r"\b(?:про|об|о|about)\s+[«\"'“]?[А-ЯЁA-Z][^?!.\n]{2,80}")
    _quoted_subject = re.compile(r"[«“\"]([^»”\"]{2,80})[»”\"]")
    _game_context = re.compile(r"\bигр\w*|\bgame\b", re.IGNORECASE)
    _catalog = re.compile(r"\bкаталог\w*|\bу нас\b.{0,50}\bигр\w*|\bигр\w*.{0,50}\bу нас\b", re.IGNORECASE)
    _lookup_intent = re.compile(r"\b(?:найд\w*|поиск\w*|ищ\w*|провер\w*|покаж\w*|перечисл\w*|какие|есть ли)\b", re.IGNORECASE)
    _catalog_excluded = re.compile(r"\bне\s+(?:в|из|по)\s+(?:нашем\s+)?каталог\w*|\bне\s+(?:используй|проверяй)\s+каталог\w*", re.IGNORECASE)
    _web_request = re.compile(r"\b(?:в интернете|в сети|веб[- ]?поиск|web search|на steam|в steam)\b", re.IGNORECASE)
    _creative_request = re.compile(r"\b(?:придумай|сочини|создай|сгенерируй|invent|create)\b", re.IGNORECASE)
    _catalog_claim = re.compile(
        r"\b(?:каталог\w*[^.!?\n]{0,100}\b(?:есть|нет|найден\w*|отсутств\w*|содерж\w*)|"
        r"(?:есть|нет|найден\w*|отсутств\w*)[^.!?\n]{0,100}\bкаталог\w*)",
        re.IGNORECASE,
    )
    _uncertain_answer = re.compile(
        r"\b(?:не знаю|не слышал\w*|не знаком\w*|не уверен\w*|не известн\w*|неизвестн\w*|"
        r"нет (?:у меня )?(?:над[её]жных|достоверных) сведений|"
        r"нет (?:достоверной|над[её]жной) информации|"
        r"не располагаю [^.]{0,40}информацией|"
        r"не могу (?:подтвердить|проверить|сказать)|"
        r"не буду (?:выдумывать|придумывать)|"
        r"ничего достоверного|скорее всего|возможно|вероятно|похоже|не название конкретной игры|"
        r"i don.t know|not familiar|no reliable information|can.t verify)\b",
        re.IGNORECASE,
    )

    def retry(self, user_message: str, draft_answer: str, tools: list[dict[str, Any]],
              mapping: dict[str, tuple[str, str]]) -> LookupRetry | None:
        """Выбирает только уже разрешённые `search_games` и не более одного ретрая."""

        catalog_tools = [tool for tool in tools
                         if mapping.get(tool["function"]["name"], (None, None))[1] == "search_games"]
        if not catalog_tools or self._catalog_excluded.search(user_message):
            return None
        if self._catalog.search(user_message) and self._lookup_intent.search(user_message):
            return LookupRetry("explicit_catalog_request", catalog_tools)
        if self._web_request.search(user_message) or self._creative_request.search(user_message):
            return None
        named_game = bool(self._game_title.search(user_message))
        if not named_game and (self._named_subject.search(user_message) or self._quoted_subject.search(user_message)):
            named_game = bool(self._game_context.search(user_message + " " + draft_answer[:400]))
        if named_game:
            if self._catalog_claim.search(draft_answer[:800]):
                return LookupRetry("ungrounded_catalog_claim", catalog_tools)
            if self._uncertain_answer.search(draft_answer[:600]):
                return LookupRetry("unknown_named_game", catalog_tools)
        return None
