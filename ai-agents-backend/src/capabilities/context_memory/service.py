"""Координатор подключаемых стратегий управления контекстом."""

from agent_core.models import AgentError
from capabilities.context_memory.runtime import ContextRequest, PreparedContext
from capabilities.context_memory.summarizer import LlmSummarizer
from capabilities.context_memory.strategies import (
    BranchingStrategy,
    FullHistoryStrategy,
    SlidingWindowStrategy,
    StickyFactsStrategy,
    SummaryStrategy,
)
from capabilities.context_memory.strategies.common import full_context
from capabilities.context_memory.strategies.summary import compression_cut


class ContextStrategyRegistry:
    """Сопоставляет стабильный идентификатор режима с одной реализацией стратегии."""

    def __init__(self, strategies=()):
        self._strategies = {}
        for strategy in strategies:
            self.register(strategy)

    def register(self, strategy) -> None:
        """Регистрирует стратегию и запрещает неявное переопределение режима."""

        if strategy.mode in self._strategies:
            raise ValueError(f"Context strategy already registered: {strategy.mode}")
        self._strategies[strategy.mode] = strategy

    def get(self, mode: str):
        try:
            return self._strategies[mode]
        except KeyError as exc:
            raise AgentError("context_not_supported", f"Стратегия контекста не подключена: {mode}", 501) from exc

    @property
    def modes(self) -> list[str]:
        return list(self._strategies)


class ContextMemory:
    """Стабильный фасад агента над реестром независимых стратегий."""

    def __init__(self, repository, summarizer, facts_repository=None, facts_extractor=None):
        self.repository = repository  # Обратная совместимость API Дня 9.
        self.summarizer = summarizer  # Оставляем доступным для настройки и тестов Дня 9.
        self.summary_repository = repository
        self.facts_repository = facts_repository
        strategies = [
            FullHistoryStrategy(),
            SummaryStrategy(repository, summarizer),
            SlidingWindowStrategy(),
            BranchingStrategy(),
        ]
        if facts_repository is not None and facts_extractor is not None:
            strategies.append(StickyFactsStrategy(facts_repository, facts_extractor))
        self.registry = ContextStrategyRegistry(strategies)

    async def prepare(self, conversation, system: str, text: str, model_id: str, *, generate: bool) -> PreparedContext:
        """Выбирает стратегию из сохранённых настроек конкретного диалога."""

        request = ContextRequest(conversation=conversation, system=system, text=text,
                                 model_id=model_id, generate=generate)
        return await self.registry.get(conversation.context_settings.mode).prepare(request)


__all__ = [
    "ContextMemory",
    "ContextStrategyRegistry",
    "LlmSummarizer",
    "PreparedContext",
    "compression_cut",
    "full_context",
]
