"""Разрешённые модели и тарифы из серверного JSON-каталога."""
import json
from datetime import datetime, UTC
from decimal import Decimal
from pathlib import Path
from agent_core.models import AgentError
from capabilities.token_accounting.models import ModelSpec


class ModelCatalog:
    """Проверяет выбор модели и связывает публичный ID с провайдером."""
    def __init__(self, models: list[ModelSpec]):
        self.models = {m.id: m for m in models}

    @classmethod
    def load(cls, path: Path, available: dict[str, bool]):
        records = json.loads(path.read_text(encoding="utf-8"))
        return cls([ModelSpec(**r, available=available.get(r["provider"], False)) for r in records])

    def get(self, model_id: str, require_available: bool = True) -> ModelSpec:
        model = self.models.get(model_id)
        if model is None:
            raise AgentError("unknown_model", "Выберите модель из серверного каталога", 422)
        if require_available and not model.available:
            raise AgentError("llm_not_configured", "Ключ выбранного провайдера не настроен", 503)
        return model

    def pricing_at(self, model_id: str, timestamp: str, returned_model: str | None = None):
        """Фиксирует тариф по времени UTC и фактически возвращённой модели."""
        spec = self.get(model_id, False)
        if spec.provider == "deepseek" and returned_model == "deepseek-flash":
            spec = self.get("deepseek-v4-flash", False)
        pricing = spec.pricing.model_copy(deep=True)
        time = datetime.fromisoformat(timestamp).astimezone(UTC)
        if spec.provider == "deepseek" and not (time.weekday() < 5 and (1 <= time.hour < 4 or 6 <= time.hour < 10)):
            pricing.input_usd *= Decimal("0.5")
            pricing.cached_input_usd *= Decimal("0.5")
            pricing.output_usd *= Decimal("0.5")
            pricing.label = pricing.label.replace("Peak", "Off-peak")
        return pricing


class ProviderRouter:
    """Направляет вызов в адаптер модели; параметры провайдеров изолированы."""
    def __init__(self, catalog: ModelCatalog, providers: dict):
        self.catalog = catalog
        self.providers = providers

    async def complete(self, messages, *, model, temperature, max_tokens):
        spec = self.catalog.get(model)
        return await self.providers[spec.provider].complete(
            messages, model=spec.model, temperature=temperature, max_tokens=max_tokens,
        )
