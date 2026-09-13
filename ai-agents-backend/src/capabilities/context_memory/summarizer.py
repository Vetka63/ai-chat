"""LLM-адаптер создания накопительной сводки истории."""

import asyncio
import json
from pathlib import Path
from time import perf_counter

from agent_core.models import AgentError, new_id, now
from capabilities.token_accounting.models import CompressionDetails, RunRecord
from .models import SummaryState
from .strategies.common import full_context


class LlmSummarizer:
    """Создаёт сводку и учитывает её как отдельный запуск purpose=summary."""

    def __init__(self, llm, accounting, catalog, max_tokens: int | None = None, prompt: str | None = None):
        self.llm, self.accounting, self.catalog = llm, accounting, catalog
        self.max_tokens = max_tokens
        self.prompt = prompt if prompt is not None else Path(__file__).with_name("summary_prompt.txt").read_text(encoding="utf-8").strip()

    async def summarize(self, conversation, model_id, previous, segment, covered, revision):
        payload = json.dumps({"previous_summary": previous.text if previous else None,
                              "messages": [m.model_dump() for m in segment]}, ensure_ascii=False)
        messages = full_context(self.prompt, [], payload)
        spec = self.catalog.get(model_id)
        estimate = await asyncio.to_thread(self.accounting.estimate, messages, [], payload, spec, self.max_tokens)
        run = RunRecord(id=new_id(), agent_id=conversation.agent_id, conversation_id=conversation.id,
            created_at=now(), model_id=spec.id, provider=spec.provider, requested_model=spec.model,
            user_index=len(conversation.messages), purpose="summary", estimate=estimate,
            pricing=self.catalog.pricing_at(model_id, now()),
            compression=CompressionDetails(history_messages=len(conversation.messages),
                segment_start=covered - len(segment) + 1, segment_end=covered,
                previous_covered=previous.covered_messages if previous else 0,
                retained_messages=len(conversation.messages) - covered,
                keep_last=conversation.context_settings.keep_last,
                summarize_every=conversation.context_settings.summarize_every, revision=revision))
        await self.accounting.record(run)
        started = perf_counter()
        try:
            result = await self.llm.complete(messages, model=model_id, temperature=0, max_tokens=self.max_tokens)
            run.usage, run.returned_model, run.finish_reason = result.usage, result.model, result.finish_reason
            run.pricing = self.catalog.pricing_at(model_id, run.created_at, result.model)
            if not result.content.strip() or result.finish_reason != "stop":
                raise AgentError("invalid_summary", "Сводка пуста или не завершена; прежняя память сохранена", 502)
            state = SummaryState(text=result.content.strip(), covered_messages=covered, revision=revision,
                                 updated_at=now(), model_id=model_id, returned_model=result.model, run_id=run.id)
            run.status = "success"
            return state, run
        except AgentError as exc:
            run.status, run.error_code, run.error_message = "error", exc.code, exc.message
            run.provider_status = exc.provider_status
            raise AgentError("summary_failed", f"Не удалось сжать историю: {exc.message}. Вопрос сохранён; ответ не запрашивался.",
                             exc.status, exc.provider_status) from exc
        finally:
            run.duration_ms = round((perf_counter() - started) * 1000)
            await self.accounting.record(run)
