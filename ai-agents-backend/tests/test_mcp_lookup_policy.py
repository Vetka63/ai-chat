"""Правила повторного выбора игрового каталога после ответа без tool_calls."""

import pytest

from agents.mcp_games.lookup_policy import GameCatalogLookupPolicy


TOOLS = [{"type": "function", "function": {"name": "java_games_mock__search_games"}}]
MAPPING = {"java_games_mock__search_games": ("java-games-mock", "search_games")}


@pytest.mark.parametrize(("message", "draft", "reason"), [
    ("А слышал что-то про игру Механика дождя?", "Про эту игру у меня нет надёжных сведений.", "unknown_named_game"),
    ("Что знаешь об игре «Лунный архив»?", "Мне она не известна.", "unknown_named_game"),
    ("Расскажи мне про Механику дождя", "«Механика дождя» — это, скорее всего, не название конкретной игры.", "unknown_named_game"),
    ("Ты что-нибудь знаешь о «Механике дождя»?", "Об этой игре я не уверен.", "unknown_named_game"),
    ("Что за игра Механика дождя?", "В нашем каталоге её нет.", "ungrounded_catalog_claim"),
    ("Найди в нашем каталоге игры про космос", "Уточни, где искать.", "explicit_catalog_request"),
    ("Найди у нас игры про космос", "Могу поискать позже.", "explicit_catalog_request"),
    ("Расскажи про игру Diablo II", "Diablo II — известная action-RPG от Blizzard.", None),
    ("Что такое RPG?", "Не уверен, как объяснить жанр.", None),
    ("Придумай игру Механика дождя", "Не знаю такую игру.", None),
    ("Проверь не в каталоге игру Механика дождя", "Не знаю эту игру.", None),
    ("Найди в интернете игру Механика дождя", "У меня нет надёжных сведений.", None),
])
def test_retry_only_when_catalog_is_relevant(message, draft, reason):
    decision = GameCatalogLookupPolicy().retry(message, draft, TOOLS, MAPPING)
    assert (decision.reason if decision else None) == reason
    if decision:
        assert decision.tools == TOOLS


def test_retry_cannot_enable_a_server_that_was_not_selected():
    policy = GameCatalogLookupPolicy()
    assert policy.retry("А слышал про игру Механика дождя?", "Нет надёжных сведений.", [], {}) is None
    unrelated = [{"type": "function", "function": {"name": "other__weather"}}]
    assert policy.retry("А слышал про игру Механика дождя?", "Нет надёжных сведений.",
                        unrelated, {"other__weather": ("other", "weather")}) is None
