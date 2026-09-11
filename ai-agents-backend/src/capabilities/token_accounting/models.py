"""Модели измерений: оценки контекста отделены от фактического расхода API."""
from decimal import Decimal
from typing import Literal
from pydantic import BaseModel, Field


class TokenUsage(BaseModel):
    """Счётчики провайдера; отсутствие usage означает неизвестный расход."""
    prompt_tokens: int = Field(ge=0)
    completion_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)
    cached_tokens: int | None = Field(default=None, ge=0)
    reasoning_tokens: int | None = Field(default=None, ge=0)


class Pricing(BaseModel):
    """Снимок тарифов USD за миллион токенов на дату эксперимента."""
    input_usd: Decimal
    cached_input_usd: Decimal
    output_usd: Decimal
    label: str
    source: str
    checked_at: str


class ModelSpec(BaseModel):
    """Публичная запись серверного каталога без ключей и адресов подключения."""
    id: str
    provider: str
    model: str
    title: str
    context_window: int = Field(gt=0)
    max_output_tokens: int = Field(gt=0)
    available: bool = True
    model_url: str = ""
    pricing: Pricing


class ContextEstimate(BaseModel):
    """Предварительная оценка перед отправкой; не ограничивает вызов модели."""
    current_message_tokens: int
    history_tokens: int
    system_tokens: int
    prompt_tokens: int
    reserved_output_tokens: int | None = None
    context_window: int
    occupancy_percent: float
    exceeds_context: bool
    method: str
    context_mode: Literal["full", "summary"] = "full"
    full_prompt_tokens: int | None = None
    summary_tokens: int = 0
    summarized_messages: int = 0
    summary_revision: int | None = None
    pending_summary: bool = False
    history_message_count: int | None = None
    unsummarized_old_messages: int | None = None
    messages_until_summary: int | None = None
    retained_message_count: int | None = None


class CompressionDetails(BaseModel):
    """Снимок попытки сжатия: номера сообщений с единицы, до текущего вопроса."""
    history_messages: int
    segment_start: int
    segment_end: int
    previous_covered: int
    retained_messages: int
    keep_last: int
    summarize_every: int
    revision: int


class RunRecord(BaseModel):
    """Один вызов с сохранённым снимком модели, тарифов и контекста."""
    id: str
    agent_id: str
    conversation_id: str
    created_at: str
    model_id: str
    provider: str
    requested_model: str
    returned_model: str | None = None
    user_index: int
    assistant_index: int | None = None
    status: Literal["pending", "success", "error", "interrupted"] = "pending"
    estimate: ContextEstimate
    usage: TokenUsage | None = None
    estimated_cost_usd: Decimal | None = None
    pricing: Pricing
    finish_reason: str | None = None
    duration_ms: int | None = None
    error_code: str | None = None
    error_message: str | None = None
    provider_status: int | None = None
    purpose: Literal["dialogue", "summary"] = "dialogue"
    compression: CompressionDetails | None = None


class TokenSavingsBreakdown(BaseModel):
    """Оценка эффекта сжатия для одной модели и одного метода подсчёта."""

    model_id: str
    requested_model: str
    method: str
    compared_dialogue_runs: int = 0
    unknown_dialogue_runs: int = 0
    full_prompt_tokens: int = 0
    compressed_prompt_tokens: int = 0
    gross_input_savings_tokens: int = 0
    summary_runs: int = 0
    summary_usage_tokens: int = 0
    unknown_summary_runs: int = 0
    net_savings_tokens: int | None = None
    net_savings_percent: float | None = None


class TokenSavings(BaseModel):
    """Накопительная оценка экономии диалога с явным признаком полноты."""

    compared_dialogue_runs: int = 0
    unknown_dialogue_runs: int = 0
    full_prompt_tokens: int = 0
    compressed_prompt_tokens: int = 0
    gross_input_savings_tokens: int = 0
    summary_runs: int = 0
    summary_usage_tokens: int = 0
    unknown_summary_runs: int = 0
    net_savings_tokens: int | None = None
    net_savings_percent: float | None = None
    complete: bool = True
    mixed_models: bool = False
    by_model: list[TokenSavingsBreakdown] = Field(default_factory=list)
