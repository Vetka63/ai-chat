"""Подключаемое измерение контекста и расходов для любого диалогового агента."""
from decimal import Decimal
from typing import Protocol
from capabilities.token_accounting.models import (
    ContextEstimate,
    ModelSpec,
    Pricing,
    RunRecord,
    TokenSavings,
    TokenSavingsBreakdown,
    TokenUsage,
)


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


def calculate_token_savings(runs: list[RunRecord]) -> TokenSavings:
    """Сопоставляет локальное уменьшение prompt с реальным расходом summary.

    Основной вызов учитывается, только если провайдер вернул usage: иначе нельзя
    утверждать, что сокращённый prompt действительно был обработан. Затраты на
    сжатие берутся из API total_tokens и включают вход и выход суммаризатора.
    """

    buckets: dict[tuple[str, str, str], dict[str, int]] = {}

    def bucket(run: RunRecord) -> dict[str, int]:
        key = (run.model_id, run.requested_model, run.estimate.method)
        return buckets.setdefault(key, {
            "compared_dialogue_runs": 0,
            "unknown_dialogue_runs": 0,
            "full_prompt_tokens": 0,
            "compressed_prompt_tokens": 0,
            "summary_runs": 0,
            "summary_usage_tokens": 0,
            "unknown_summary_runs": 0,
        })

    for run in runs:
        values = bucket(run)
        if run.purpose == "summary":
            values["summary_runs"] += 1
            if run.usage is None:
                values["unknown_summary_runs"] += 1
            else:
                values["summary_usage_tokens"] += run.usage.total_tokens
            continue

        estimate = run.estimate
        if estimate.context_mode != "summary" or estimate.full_prompt_tokens is None:
            continue
        if run.usage is None:
            values["unknown_dialogue_runs"] += 1
            continue
        values["compared_dialogue_runs"] += 1
        values["full_prompt_tokens"] += estimate.full_prompt_tokens
        values["compressed_prompt_tokens"] += estimate.prompt_tokens

    by_model: list[TokenSavingsBreakdown] = []
    for (model_id, requested_model, method), values in buckets.items():
        # Пустые группы от обычных full-вызовов не нужны в отчёте.
        if not any(values.values()):
            continue
        gross = values["full_prompt_tokens"] - values["compressed_prompt_tokens"]
        complete = values["unknown_dialogue_runs"] == 0 and values["unknown_summary_runs"] == 0
        net = gross - values["summary_usage_tokens"] if complete else None
        percent = round(100 * net / values["full_prompt_tokens"], 2) if net is not None and values["full_prompt_tokens"] else None
        by_model.append(TokenSavingsBreakdown(
            model_id=model_id,
            requested_model=requested_model,
            method=method,
            gross_input_savings_tokens=gross,
            net_savings_tokens=net,
            net_savings_percent=percent,
            **values,
        ))

    totals = {
        field: sum(getattr(item, field) for item in by_model)
        for field in (
            "compared_dialogue_runs", "unknown_dialogue_runs", "full_prompt_tokens",
            "compressed_prompt_tokens", "gross_input_savings_tokens", "summary_runs",
            "summary_usage_tokens", "unknown_summary_runs",
        )
    }
    complete = totals["unknown_dialogue_runs"] == 0 and totals["unknown_summary_runs"] == 0
    net = totals["gross_input_savings_tokens"] - totals["summary_usage_tokens"] if complete else None
    percent = round(100 * net / totals["full_prompt_tokens"], 2) if net is not None and totals["full_prompt_tokens"] else None
    return TokenSavings(
        **totals,
        net_savings_tokens=net,
        net_savings_percent=percent,
        complete=complete,
        mixed_models=len(by_model) > 1,
        by_model=by_model,
    )


class TokenAccounting:
    """Измеряет подготовленный контекст, не блокируя и не изменяя его."""
    def __init__(self, estimators: dict[str, TokenEstimator], repository: UsageRepository):
        self.estimators = estimators
        self.repository = repository

    def estimate(self, messages: list, history: list, text: str, model: ModelSpec, max_tokens: int | None) -> ContextEstimate:
        estimator = self.estimators[model.provider]
        prompt = estimator.count_messages(messages)
        return ContextEstimate(
            current_message_tokens=estimator.count_text(text),
            history_tokens=sum(estimator.count_text(m.content) for m in history),
            system_tokens=sum(estimator.count_text(m.content) for m in messages if m.role == "system"),
            prompt_tokens=prompt, reserved_output_tokens=max_tokens,
            context_window=model.context_window,
            occupancy_percent=round(100 * (prompt + (max_tokens or 0)) / model.context_window, 2),
            exceeds_context=prompt + (max_tokens or 0) > model.context_window, method=estimator.method,
        )

    async def record(self, run: RunRecord) -> None:
        run.estimated_cost_usd = calculate_cost(run.usage, run.pricing)
        await self.repository.save(run)

    async def savings(self, agent_id: str, conversation_id: str) -> TokenSavings:
        """Возвращает накопительный эффект сжатия по сохранённым вызовам."""
        return calculate_token_savings(await self.repository.list(agent_id, conversation_id))
