"""LLM-извлечение key-value facts со строгой проверкой результата."""

import asyncio
import json
from pathlib import Path
from time import perf_counter

from pydantic import BaseModel, ConfigDict

from agent_core.models import AgentError, new_id, now
from capabilities.token_accounting.models import RunRecord
from .models import FactsState
from .strategies.common import full_context


class FactsPayload(BaseModel):
    """Единственный разрешённый формат ответа извлекателя фактов."""

    model_config = ConfigDict(extra="forbid")
    facts: dict[str, str]


def parse_facts(text: str) -> dict[str, str]:
    """Извлекает один JSON-объект, допуская только внешние Markdown fences."""

    candidate = text.strip()
    if candidate.startswith("```") and candidate.endswith("```"):
        candidate = candidate[3:-3].strip()
        if candidate.lower().startswith("json"):
            candidate = candidate[4:].strip()
    try:
        payload = FactsPayload.model_validate(json.loads(candidate))
    except (json.JSONDecodeError, ValueError) as exc:
        raise AgentError("invalid_facts", "Модель вернула некорректный JSON для facts", 502) from exc
    return {key.strip(): value.strip() for key, value in payload.facts.items() if key.strip() and value.strip()}


class LlmFactsExtractor:
    """Обновляет facts после пользовательского сообщения и записывает отдельный run."""

    def __init__(self, llm, accounting, catalog, max_tokens: int | None = None, prompt: str | None = None):
        self.llm, self.accounting, self.catalog = llm, accounting, catalog
        self.max_tokens = max_tokens
        self.prompt = prompt if prompt is not None else Path(__file__).with_name("facts_prompt.txt").read_text(encoding="utf-8").strip()

    async def update(self, conversation, model_id: str, previous: FactsState | None, text: str):
        payload = json.dumps({"existing_facts": previous.facts if previous else {}, "user_message": text}, ensure_ascii=False)
        messages = full_context(self.prompt, [], payload)
        spec = self.catalog.get(model_id)
        estimate = await asyncio.to_thread(self.accounting.estimate, messages, [], payload, spec, self.max_tokens)
        run = RunRecord(id=new_id(), agent_id=conversation.agent_id, conversation_id=conversation.id,
            created_at=now(), model_id=spec.id, provider=spec.provider, requested_model=spec.model,
            user_index=len(conversation.messages), purpose="facts", estimate=estimate,
            pricing=self.catalog.pricing_at(model_id, now()))
        await self.accounting.record(run)
        started = perf_counter()
        try:
            result = await self.llm.complete(messages, model=model_id, temperature=0, max_tokens=self.max_tokens)
            run.usage, run.returned_model, run.finish_reason = result.usage, result.model, result.finish_reason
            run.pricing = self.catalog.pricing_at(model_id, run.created_at, result.model)
            if not result.content.strip() or result.finish_reason != "stop":
                raise AgentError("invalid_facts", "Ответ facts пуст или не завершён", 502)
            facts = parse_facts(result.content)
            state = FactsState(facts=facts, revision=(previous.revision if previous else 0) + 1,
                updated_at=now(), updated_from_message=len(conversation.messages) + 1,
                model_id=model_id, returned_model=result.model, run_id=run.id)
            run.status = "success"
            return state, run, None
        except AgentError as exc:
            run.status, run.error_code, run.error_message = "error", exc.code, exc.message
            run.provider_status = exc.provider_status
            return previous, run, "Facts не обновлены: " + exc.message
        finally:
            run.duration_ms = round((perf_counter() - started) * 1000)
            await self.accounting.record(run)
