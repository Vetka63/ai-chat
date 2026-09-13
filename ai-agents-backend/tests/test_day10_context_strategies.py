"""День 10: независимые стратегии окна, sticky facts и ветвления."""

import json
from pathlib import Path
import pytest
from fastapi.testclient import TestClient

from agent_core.models import AgentCommand, Completion
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from capabilities.context_memory.facts import LlmFactsExtractor
from capabilities.context_memory.models import ContextSettings, FactsState
from capabilities.context_memory.service import ContextMemory, LlmSummarizer
from capabilities.token_accounting.models import TokenUsage
from capabilities.token_accounting.service import TokenAccounting
from infrastructure.model_catalog import ModelCatalog
from infrastructure.sqlite_branching import SqliteBranchRepository
from infrastructure.sqlite_facts import SqliteFactsRepository
from infrastructure.sqlite_store import SqliteConversationStore
from infrastructure.sqlite_summary import SqliteSummaryRepository
from infrastructure.sqlite_usage import SqliteUsageRepository
from infrastructure.tokenizers import ByteEstimate
from application.main import create_app
from application.settings import Settings


class Day10Llm:
    """Отличает служебный JSON-вызов facts от основного ответа."""

    def __init__(self, facts_outputs=None):
        self.calls = []
        self.facts_outputs = list(facts_outputs or ['{"facts":{}}'])

    async def complete(self, messages, **kwargs):
        purpose = 'facts' if 'key-value' in messages[0].content else 'dialogue'
        self.calls.append((purpose, messages, kwargs))
        content = self.facts_outputs.pop(0) if purpose == 'facts' else 'Основной ответ'
        return Completion(content=content, model=kwargs['model'], finish_reason='stop',
                          usage=TokenUsage(prompt_tokens=50, completion_tokens=10, total_tokens=60))


async def setup(tmp_path, mode, *, keep_last=4, facts_outputs=None):
    store = SqliteConversationStore(tmp_path/'day10.sqlite3')
    await store.initialize()
    summaries, facts, usage = SqliteSummaryRepository(store), SqliteFactsRepository(store), SqliteUsageRepository(store)
    branches = SqliteBranchRepository(store)
    await summaries.initialize()
    await facts.initialize()
    await usage.initialize()
    await branches.initialize()
    models = ModelCatalog.load(Path(__file__).parents[1]/'src/application/models.json', {'deepseek': True})
    accounting = TokenAccounting({'deepseek': ByteEstimate()}, usage)
    llm = Day10Llm(facts_outputs)
    memory = ContextMemory(summaries, LlmSummarizer(llm, accounting, models), facts,
                           LlmFactsExtractor(llm, accounting, models))
    agent = build_dialogue_agent(
        DialogueAgentConfig(model='deepseek-v4-flash', system_prompt='System'), llm, store,
        catalog=models, accounting=accounting, memory=memory, branch_service=branches,
    )
    conversation = await store.create('dialogue', 'Day 10')
    await store.configure_context('dialogue', conversation.id, ContextSettings(mode=mode, keep_last=keep_last))
    return agent, llm, store, facts, branches, usage, conversation


async def seed_pairs(store, conversation_id, pairs=4):
    for index in range(pairs):
        await store.append_message('dialogue', conversation_id, 'user', f'question-{index}')
        await store.append_message('dialogue', conversation_id, 'assistant', f'answer-{index}')


async def test_sliding_window_sends_only_last_n_but_keeps_originals(tmp_path):
    agent, llm, store, facts, branches, usage, conversation = await setup(tmp_path, 'sliding_window', keep_last=4)
    await seed_pairs(store, conversation.id)
    result = await agent.run(AgentCommand(conversation_id=conversation.id, message='current'))

    sent = llm.calls[-1][1]
    assert [item.content for item in sent] == ['System', 'question-2', 'answer-2', 'question-3', 'answer-3', 'current']
    assert result.run.estimate.context_mode == 'sliding_window'
    assert result.run.estimate.retained_message_count == 4
    assert result.run.estimate.discarded_message_count == 4
    assert len((await store.get('dialogue', conversation.id)).messages) == 10


async def test_sliding_window_accepts_odd_n_and_keeps_exact_tail(tmp_path):
    agent, llm, store, facts, branches, usage, conversation = await setup(
        tmp_path, 'sliding_window', keep_last=3,
    )
    await seed_pairs(store, conversation.id, pairs=3)
    await agent.run(AgentCommand(conversation_id=conversation.id, message='current'))
    assert [item.content for item in llm.calls[-1][1]] == [
        'System', 'answer-1', 'question-2', 'answer-2', 'current',
    ]


async def test_sticky_facts_updates_before_answer_and_survives_restart(tmp_path):
    outputs = [
        json.dumps({'facts': {'goal': 'Собрать ТЗ', 'budget': '500000'}}, ensure_ascii=False),
        json.dumps({'facts': {'goal': 'Собрать ТЗ', 'budget': '700000'}}, ensure_ascii=False),
    ]
    agent, llm, store, facts, branches, usage, conversation = await setup(
        tmp_path, 'sticky_facts', keep_last=2, facts_outputs=outputs,
    )
    await seed_pairs(store, conversation.id, pairs=2)
    first = await agent.run(AgentCommand(conversation_id=conversation.id, message='Бюджет 500000'))
    assert [call[0] for call in llm.calls] == ['facts', 'dialogue']
    assert first.facts.facts['budget'] == '500000'
    main_messages = llm.calls[-1][1]
    assert '"goal": "Собрать ТЗ"' in main_messages[1].content
    assert [message.content for message in main_messages[-3:]] == ['question-1', 'answer-1', 'Бюджет 500000']
    assert first.additional_runs[0].purpose == 'facts'

    second = await agent.run(AgentCommand(conversation_id=conversation.id, message='Меняем бюджет на 700000'))
    assert second.facts.revision == 2 and second.facts.facts['budget'] == '700000'
    restarted = SqliteFactsRepository(SqliteConversationStore(store.path))
    await restarted.initialize()
    assert (await restarted.get('dialogue', conversation.id)).facts['budget'] == '700000'


async def test_invalid_facts_keeps_old_state_and_still_answers(tmp_path):
    agent, llm, store, facts, branches, usage, conversation = await setup(
        tmp_path, 'sticky_facts', keep_last=2, facts_outputs=['not-json'],
    )
    old = FactsState(facts={'goal': 'Старое ТЗ'}, revision=1, updated_at='2026-01-01T00:00:00Z',
                     updated_from_message=1, model_id='deepseek-v4-flash', returned_model='deepseek-flash', run_id='old')
    await facts.save('dialogue', conversation.id, old)
    result = await agent.run(AgentCommand(conversation_id=conversation.id, message='Новое сообщение'))
    assert result.reply == 'Основной ответ'
    assert result.facts == old
    assert result.memory_warnings and 'Facts не обновлены' in result.memory_warnings[0]
    assert result.additional_runs[0].status == 'error'


async def test_checkpoint_creates_two_isolated_persistent_branches(tmp_path):
    agent, llm, store, facts, branches, usage, conversation = await setup(tmp_path, 'branching')
    await seed_pairs(store, conversation.id, pairs=2)
    old_facts = FactsState(facts={'goal': 'ТЗ'}, revision=1, updated_at='2026-01-01T00:00:00Z',
                           updated_from_message=1, model_id='deepseek-v4-flash', returned_model='model', run_id='run')
    await facts.save('dialogue', conversation.id, old_facts)
    checkpoint = await agent.create_checkpoint(conversation.id, 'Архитектурный выбор')
    await store.append_message('dialogue', conversation.id, 'user', 'Не должно попасть в ветки')
    children = await agent.create_branches(checkpoint.id, ['Монолит', 'Микросервисы'])

    assert len(children) == 2 and all(item.context_settings.mode == 'branching' for item in children)
    assert all(item.parent_conversation_id == conversation.id and item.checkpoint_id == checkpoint.id for item in children)
    histories = [await store.get('dialogue', item.id) for item in children]
    copied_facts = [await facts.get('dialogue', item.id) for item in children]
    assert all(len(item.messages) == 4 for item in histories)
    assert all(item.facts == {'goal': 'ТЗ'} for item in copied_facts)

    await store.append_message('dialogue', children[0].id, 'user', 'Только монолит')
    assert len((await store.get('dialogue', children[0].id)).messages) == 5
    assert len((await store.get('dialogue', children[1].id)).messages) == 4
    restarted = SqliteBranchRepository(SqliteConversationStore(store.path))
    await restarted.initialize()
    assert (await restarted.list_checkpoints('dialogue', conversation.id))[0] == checkpoint
    with pytest.raises(Exception) as error:
        await agent.create_branches(checkpoint.id, ['Ещё A', 'Ещё B'])
    assert getattr(error.value, 'code', None) == 'branches_already_created'


def test_day10_http_switch_checkpoint_and_two_branches(tmp_path):
    with TestClient(create_app(Settings(mode='demo', database_path=tmp_path/'api.sqlite3', _env_file=None))) as client:
        root = '/api/v1/agents/dialogue/conversations'
        conversation = client.post(root, json={'title': 'ТЗ', 'context_settings': {
            'mode': 'branching', 'keep_last': 10, 'summarize_every': 10,
        }}).json()
        path = f"{root}/{conversation['id']}"
        changed = client.patch(path+'/context', json={'mode': 'full', 'keep_last': 10, 'summarize_every': 10})
        checkpoint = client.post(path+'/checkpoints', json={'title': 'Выбор'}).json()
        created = client.post(f"/api/v1/agents/dialogue/checkpoints/{checkpoint['id']}/branches",
                              json={'names': ['Ветка A', 'Ветка B']})
        duplicate = client.post(f"/api/v1/agents/dialogue/checkpoints/{checkpoint['id']}/branches",
                                json={'names': ['Ветка C', 'Ветка D']})
        detail = client.get(root+'/'+created.json()[0]['id']).json()

    assert changed.status_code == 409 and changed.json()['code'] == 'context_settings_immutable'
    assert checkpoint['message_count'] == 0
    assert created.status_code == 201 and len(created.json()) == 2
    assert duplicate.status_code == 409 and duplicate.json()['code'] == 'branches_already_created'
    assert detail['context_settings']['mode'] == 'branching'
    assert detail['parent_conversation_id'] == conversation['id']


def test_demo_http_sticky_facts_updates_and_returns_main_answer(tmp_path):
    with TestClient(create_app(Settings(mode='demo', database_path=tmp_path/'demo-facts.sqlite3', _env_file=None))) as client:
        root = '/api/v1/agents/dialogue/conversations'
        conversation = client.post(root, json={
            'context_settings': {'mode': 'sticky_facts', 'keep_last': 3, 'summarize_every': 10},
        }).json()
        result = client.post('/api/v1/agents/dialogue/runs', json={
            'conversation_id': conversation['id'], 'message': 'Цель — собрать ТЗ',
        })
        persisted = client.get(f"{root}/{conversation['id']}").json()

    assert result.status_code == 200
    assert result.json()['facts']['facts']['последнее_сообщение'] == 'Цель — собрать ТЗ'
    assert [run['purpose'] for run in persisted['runs']] == ['facts', 'dialogue']
