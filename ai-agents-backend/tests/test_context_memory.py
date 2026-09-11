"""Проверки границ сжатия, сохранности оригиналов, стоимости и восстановления памяти."""
import asyncio
import json
from pathlib import Path
import pytest
from pydantic import ValidationError
from fastapi.testclient import TestClient
from agent_core.models import AgentCommand, AgentError, Completion, Message, PreviewCommand
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.main import create_app
from application.settings import Settings
from capabilities.context_memory.models import ContextSettings
from capabilities.context_memory.service import ContextMemory, LlmSummarizer, compression_cut
from capabilities.token_accounting.models import TokenUsage
from capabilities.token_accounting.service import TokenAccounting
from infrastructure.model_catalog import ModelCatalog
from infrastructure.sqlite_store import SqliteConversationStore
from infrastructure.sqlite_summary import SqliteSummaryRepository
from infrastructure.sqlite_usage import SqliteUsageRepository
from infrastructure.tokenizers import ByteEstimate
from infrastructure.tokenizers import MistralEstimate


class Llm:
    def __init__(self):
        self.calls = []
        self.summary_failure = None

    async def complete(self, messages, **kwargs):
        is_summary = 'previous_summary' in messages[-1].content
        self.calls.append((is_summary, messages, kwargs))
        if is_summary and self.summary_failure == 'timeout':
            raise AgentError('provider_timeout', 'Timeout', 504)
        return Completion(content='' if is_summary and self.summary_failure == 'empty' else 'Important fact: CLOVER-42.',
            model=kwargs['model'], finish_reason='length' if is_summary and self.summary_failure == 'length' else 'stop',
            usage=TokenUsage(prompt_tokens=100, completion_tokens=20, total_tokens=120))


async def build(tmp_path, settings=None, pairs=10):
    store = SqliteConversationStore(tmp_path/'context.sqlite3')
    await store.initialize()
    usage, repository = SqliteUsageRepository(store), SqliteSummaryRepository(store)
    await usage.initialize()
    await repository.initialize()
    models = ModelCatalog.load(Path(__file__).parents[1]/'src/application/models.json', {'deepseek': True})
    accounting = TokenAccounting({'deepseek': ByteEstimate()}, usage)
    llm = Llm()
    memory = ContextMemory(repository, LlmSummarizer(llm, accounting, models))
    agent = build_dialogue_agent(DialogueAgentConfig(model='deepseek-v4-flash', system_prompt='Base system'), llm, store,
                                catalog=models, accounting=accounting, memory=memory)
    conv = await store.create('dialogue', 'test')
    await store.configure_context('dialogue', conv.id, settings or ContextSettings(mode='summary'))
    for i in range(pairs):
        await store.append_message('dialogue', conv.id, 'user', f'Question {i} CLOVER-42. ' + 'Old detail. '*40)
        await store.append_message('dialogue', conv.id, 'assistant', f'Answer {i}')
    return agent, llm, store, repository, usage, conv


async def test_compression_retains_tail_and_originals(tmp_path):
    agent, llm, store, repository, usage, conv = await build(tmp_path)
    original = (await store.get('dialogue', conv.id)).messages
    result = await agent.run(AgentCommand(conversation_id=conv.id, message='What is the code?'))
    assert [call[0] for call in llm.calls] == [True, False]
    summary_call = json.loads(llm.calls[0][1][-1].content)
    assert summary_call['messages'] == [m.model_dump() for m in original[:10]]
    sent = llm.calls[1][1]
    assert sent[0].role == 'system' and sent[0].content.startswith('Base system')
    assert sent[1].role == 'user'  # Не повышаем инструкции из истории до system.
    assert sent[2:-1] == original[10:]
    assert sent[-1].content == 'What is the code?'
    assert (await store.get('dialogue', conv.id)).messages[:20] == original
    assert result.run.estimate.full_prompt_tokens > result.run.estimate.prompt_tokens
    assert result.run.estimate.summarized_messages == 10
    assert result.additional_runs[0].purpose == 'summary'
    event = result.additional_runs[0].compression
    assert (event.segment_start, event.segment_end, event.retained_messages) == (1, 10, 10)
    records = await usage.list('dialogue', conv.id)
    assert len(records) == 2 and sum(r.usage.total_tokens for r in records) == 240
    assert all(r.estimated_cost_usd > 0 for r in records)


async def test_below_threshold_is_full_and_preview_never_calls_llm(tmp_path):
    agent, llm, store, repository, usage, conv = await build(tmp_path, pairs=9)
    estimate = await agent.preview(PreviewCommand(conversation_id=conv.id, message='next'))
    assert not estimate.pending_summary and not llm.calls
    result = await agent.run(AgentCommand(conversation_id=conv.id, message='next'))
    assert len(llm.calls) == 1 and not llm.calls[0][0]
    assert result.summary is None
    estimate = await agent.preview(PreviewCommand(conversation_id=conv.id, message='next'))
    assert estimate.pending_summary and len(llm.calls) == 1
    assert await repository.get('dialogue', conv.id) is None


async def test_incremental_update_only_new_prefix_and_restart(tmp_path):
    settings = ContextSettings(mode='summary', keep_last=2, summarize_every=2)
    agent, llm, store, repository, usage, conv = await build(tmp_path, settings, pairs=3)
    await agent.run(AgentCommand(conversation_id=conv.id, message='next'))
    first = await repository.get('dialogue', conv.id)
    assert first.covered_messages == 4
    # Новый объект репозитория имитирует загрузку из SQLite после рестарта.
    restarted = SqliteSummaryRepository(SqliteConversationStore(store.path))
    await restarted.initialize()
    assert await restarted.get('dialogue', conv.id) == first
    agent.memory.repository = restarted
    await agent.run(AgentCommand(conversation_id=conv.id, message='next again'))
    payload = json.loads(llm.calls[2][1][-1].content)
    assert payload['previous_summary'] == first.text
    assert len(payload['messages']) == 2 and payload['messages'][0]['content'].startswith('Question 2')
    second = await restarted.get('dialogue', conv.id)
    assert second.covered_messages == 6 and second.revision == 2


@pytest.mark.parametrize('failure', ['empty', 'length', 'timeout'])
async def test_failed_summary_preserves_old_state_and_question(tmp_path, failure):
    agent, llm, store, repository, usage, conv = await build(tmp_path, ContextSettings(mode='summary', keep_last=2, summarize_every=2), pairs=3)
    await agent.run(AgentCommand(conversation_id=conv.id, message='first'))
    before = await repository.get('dialogue', conv.id)
    llm.summary_failure = failure
    with pytest.raises(AgentError) as exc:
        await agent.run(AgentCommand(conversation_id=conv.id, message='question not lost'))
    assert exc.value.code == 'summary_failed'
    assert len(llm.calls) == 3  # Второй ответ агента не запрашивался.
    assert await repository.get('dialogue', conv.id) == before
    assert (await store.get('dialogue', conv.id)).messages[-1].content == 'question not lost'
    record = (await usage.list('dialogue', conv.id))[-1]
    assert record.purpose == 'summary' and record.status == 'error'
    assert (record.compression.segment_start, record.compression.segment_end) == (5, 6)
    assert (record.usage is None) == (failure == 'timeout')


async def test_ui_progress_counts_both_roles_and_snapshots_settings(tmp_path):
    settings = ContextSettings(mode='summary', keep_last=10, summarize_every=4)
    agent, llm, store, repository, usage, conv = await build(tmp_path, settings, pairs=7)
    preview = await agent.preview(PreviewCommand(conversation_id=conv.id, message='eighth request'))
    assert preview.history_message_count == 14
    assert preview.unsummarized_old_messages == 4 and preview.messages_until_summary == 0
    assert preview.retained_message_count == 14 and preview.pending_summary
    assert not llm.calls
    result = await agent.run(AgentCommand(conversation_id=conv.id, message='eighth request'))
    snapshot = result.additional_runs[0].compression
    assert snapshot.model_dump() == dict(history_messages=14, segment_start=1, segment_end=4,
        previous_covered=0, retained_messages=10, keep_last=10, summarize_every=4, revision=1)
    preview = await agent.preview(PreviewCommand(conversation_id=conv.id))
    assert preview.history_message_count == 16
    assert preview.retained_message_count == 12
    assert preview.unsummarized_old_messages == 2 and preview.messages_until_summary == 2
    assert not preview.pending_summary
    await agent.configure_context(conv.id, ContextSettings(mode='summary', keep_last=12, summarize_every=6))
    saved = (await usage.list('dialogue', conv.id))[0].compression
    assert saved == snapshot  # Старое событие не меняется вслед за настройками UI.


async def test_mode_switch_and_increased_tail_do_not_duplicate_prefix(tmp_path):
    agent, llm, store, repository, usage, conv = await build(tmp_path, ContextSettings(mode='summary', keep_last=2, summarize_every=2), pairs=6)
    await agent.run(AgentCommand(conversation_id=conv.id, message='next'))
    assert (await repository.get('dialogue', conv.id)).covered_messages == 10
    await agent.configure_context(conv.id, ContextSettings(mode='full'))
    await agent.run(AgentCommand(conversation_id=conv.id, message='full'))
    assert not llm.calls[-1][0] and len(llm.calls[-1][1]) == 16
    await agent.configure_context(conv.id, ContextSettings(mode='summary', keep_last=12, summarize_every=2))
    await agent.run(AgentCommand(conversation_id=conv.id, message='larger tail'))
    payload = json.loads(llm.calls[-2][1][-1].content)
    assert payload['previous_summary'] is None
    assert len(payload['messages']) == 4
    assert (await repository.get('dialogue', conv.id)).covered_messages == 4


async def test_fork_has_identical_history_but_no_summary_or_old_costs(tmp_path):
    agent, llm, store, repository, usage, conv = await build(tmp_path)
    await agent.run(AgentCommand(conversation_id=conv.id, message='next'))
    fork = await agent.fork_conversation(conv.id, ContextSettings(mode='full'))
    assert (await store.get('dialogue', fork.id)).messages == (await store.get('dialogue', conv.id)).messages
    assert await repository.get('dialogue', fork.id) is None
    assert not await usage.list('dialogue', fork.id)
    assert fork.context_settings.mode == 'full'
    with pytest.raises(AgentError):
        await repository.get('another-agent', conv.id)
    await store.delete('dialogue', conv.id)
    async with store.connection() as db:
        assert (await (await db.execute('SELECT count(*) FROM summaries')).fetchone())[0] == 0


async def test_two_concurrent_questions_do_not_compress_same_prefix_twice(tmp_path):
    agent, llm, store, repository, usage, conv = await build(tmp_path)
    await asyncio.gather(*(agent.run(AgentCommand(conversation_id=conv.id, message=f'question {i}')) for i in range(2)))
    assert sum(c[0] for c in llm.calls) == 1
    assert len((await store.get('dialogue', conv.id)).messages) == 24


def test_boundaries_and_settings_validation():
    history = [Message(role='user' if i % 2 == 0 else 'assistant', content=str(i)) for i in range(8)]
    history.append(Message(role='user', content='failed request'))
    assert compression_cut(history, 2) == 6
    assert compression_cut(history, 10) == 0
    with pytest.raises(ValidationError):
        ContextSettings(keep_last=3)
    with pytest.raises(ValidationError):
        ContextSettings(mode='unknown')


def test_http_context_fork_and_defaults(tmp_path):
    with TestClient(create_app(Settings(mode='demo', database_path=tmp_path/'http.sqlite3', _env_file=None))) as client:
        root = '/api/v1/agents/dialogue/conversations'
        conv = client.post(root, json={}).json()
        assert conv['context_settings']['mode'] == 'full'
        path = root+'/'+conv['id']
        assert client.patch(path+'/context', json={'mode': 'summary', 'keep_last': 2, 'summarize_every': 2}).status_code == 200
        assert client.patch(path+'/context', json={'keep_last': 1}).status_code == 422
        assert client.get(path).json()['context_settings']['mode'] == 'summary'
        fork = client.post(path+'/fork', json={'mode': 'full'}).json()
        assert fork['id'] != conv['id'] and fork['context_settings']['mode'] == 'full'


async def test_official_mistral_tokenizer_accepts_summary_context(tmp_path):
    path = Path(__file__).parents[1]/'tokenizers/ministral-3b/tekken.json'
    if not path.exists():
        pytest.skip('Download official tokenizer first')
    agent, llm, store, repository, usage, conv = await build(tmp_path)
    await agent.run(AgentCommand(conversation_id=conv.id, message='next'))
    counter = MistralEstimate(str(path))
    assert counter.count_messages(llm.calls[-1][1]) > 0
    # После неудачного ответа могут остаться два user подряд: это тоже допустимый контекст.
    assert counter.count_messages(llm.calls[-1][1] + [Message(role='user', content='retry')]) > 0
