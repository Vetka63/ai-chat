"""Общий жизненный цикл измеряемого LLM-вызова и проверки его результата."""
import asyncio
from contextlib import asynccontextmanager
from time import perf_counter

from agent_core.models import AgentError, new_id, now
from .models import RunRecord


class RecordedLlmCall:
    """Сохраняет usage даже при отклонении ответа политикой вызывающего агента."""
    def __init__(self, llm, accounting, catalog):
        self.llm, self.accounting, self.catalog = llm, accounting, catalog

    @asynccontextmanager
    async def invoke(self, *, agent_id, conversation_id, messages, spec, estimate,
                     user_index, temperature, max_tokens, purpose='dialogue', memory_context=None):
        run = RunRecord(id=new_id(), agent_id=agent_id, conversation_id=conversation_id,
            created_at=now(), model_id=spec.id, provider=spec.provider, requested_model=spec.model,
            user_index=user_index, purpose=purpose, estimate=estimate,
            pricing=self.catalog.pricing_at(spec.id, now()), memory_context=memory_context)
        await self.accounting.record(run)
        started = perf_counter()
        try:
            completion = await self.llm.complete(messages, model=spec.id, temperature=temperature, max_tokens=max_tokens)
            run.usage, run.returned_model, run.finish_reason = completion.usage, completion.model, completion.finish_reason
            run.pricing = self.catalog.pricing_at(spec.id, run.created_at, completion.model)
            yield completion, run
            run.status = 'success'
        except AgentError as exc:
            run.status, run.error_code, run.error_message = 'error', exc.code, exc.message
            run.provider_status = exc.provider_status
            raise
        except asyncio.CancelledError:
            run.status, run.error_code = 'interrupted', 'cancelled'
            raise
        except Exception:
            run.status, run.error_code = 'error', 'internal_error'
            run.error_message = 'Не удалось обработать результат вызова'
            raise
        finally:
            run.duration_ms = round((perf_counter() - started) * 1000)
            await self.accounting.record(run)
