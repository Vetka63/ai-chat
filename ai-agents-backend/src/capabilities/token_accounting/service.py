"""Подключаемое измерение контекста и расходов для любого диалогового агента."""
from decimal import Decimal
from typing import Protocol
from capabilities.token_accounting.models import ContextEstimate, ModelSpec, Pricing, RunRecord, TokenUsage


class TokenEstimator(Protocol):
    """Локальный счётчик с явным названием метода токенизации."""
    method: str
    def count_text(self, text: str) -> int: ...
    def count_messages(self, messages: list) -> int: ...


class UsageRepository(Protocol):
    """Хранит метрики независимо от реализации агента и UI."""
    async def save(self, record: RunRecord) -> None: ...
    async def list(self, agent_id: str, conversation_id: str) -> list[RunRecord]: ...


def calculate_cost(usage: TokenUsage | None, pricing: Pricing) -> Decimal | None:
    """Считает оценку по тарифу; неизвестный кэш считается cache miss."""
    if usage is None:
        return None
    cached = min(usage.cached_tokens or 0, usage.prompt_tokens)
    return ((usage.prompt_tokens - cached) * pricing.input_usd
            + cached * pricing.cached_input_usd
            + usage.completion_tokens * pricing.output_usd) / Decimal(1_000_000)


class TokenAccounting:
    """Измеряет подготовленный контекст, не блокируя и не изменяя его."""
    def __init__(self, estimators: dict[str, TokenEstimator], repository: UsageRepository):
        self.estimators = estimators
        self.repository = repository

    def estimate(self, messages: list, history: list, text: str, model: ModelSpec, max_tokens: int) -> ContextEstimate:
        estimator = self.estimators[model.provider]
        prompt = estimator.count_messages(messages)
        return ContextEstimate(
            current_message_tokens=estimator.count_text(text),
            history_tokens=sum(estimator.count_text(m.content) for m in history),
            system_tokens=sum(estimator.count_text(m.content) for m in messages if m.role == "system"),
            prompt_tokens=prompt, reserved_output_tokens=max_tokens,
            context_window=model.context_window,
            occupancy_percent=round(100 * (prompt + max_tokens) / model.context_window, 2),
            exceeds_context=prompt + max_tokens > model.context_window, method=estimator.method,
        )

    async def record(self, run: RunRecord) -> None:
        run.estimated_cost_usd = calculate_cost(run.usage, run.pricing)
        await self.repository.save(run)
