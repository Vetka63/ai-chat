"""День 11: изоляция, явное подтверждение, восстановление и состав LLM-запроса."""
import json
import sqlite3

import pytest
from fastapi.testclient import TestClient

from agent_core.models import AgentError, Completion
from application.main import create_app
from application.settings import Settings
from capabilities.token_accounting.models import TokenUsage

BASE = '/api/v1/agents/algorithm_coach'


class ObservedLlm:
    """Записывает фактический контекст и позволяет моделировать ошибки провайдера."""
    def __init__(self):
        self.calls = []
        self.answer = 'Разбор решения без запуска кода.'
        self.error = None
        self.before_return = None

    async def complete(self, messages, **kwargs):
        self.calls.append(messages)
        if self.before_return:
            await self.before_return()
        if self.error:
            raise self.error
        return Completion(content=self.answer, model=kwargs['model'], finish_reason='stop',
            usage=TokenUsage(prompt_tokens=100, completion_tokens=20, total_tokens=120))


@pytest.fixture
def rig(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path/'test.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        llm = ObservedLlm()
        client.app.state.registry.get('algorithm_coach').calls.llm = llm
        yield client, llm


def create(client, **extra):
    response = client.post(BASE+'/conversations', json={
        'title': 'Проверка памяти', 'context_settings': {'mode': 'sliding_window', 'keep_last': 2}, **extra})
    assert response.status_code == 201, response.text
    return response.json()['id']


def path(chat):
    return BASE+'/conversations/'+chat


def memory(client, chat):
    response = client.get(path(chat)+'/memory')
    assert response.status_code == 200, response.text
    return response.json()


def versions(workspace):
    return {'task_revision': workspace['task']['revision'], 'profile_revision': workspace['profile']['memory_revision'],
            'preferences_revision': workspace['profile']['revision']}


def save(client, chat, layer='working', key='goal', value='Цель задачи', **extra):
    response = client.post(path(chat)+'/memory/entries',
        json={**versions(memory(client, chat)), 'layer': layer, 'key': key, 'value': value, **extra})
    assert response.status_code == 200, response.text
    return response.json()


def run(client, chat, text='Новый вопрос'):
    return client.post(BASE+'/runs', json={'conversation_id': chat, 'message': text, 'max_output_tokens': 1200})


def propose(client, llm, chat, candidates):
    source = next(m for m in reversed(memory(client, chat)['short_term']) if m['role'] == 'user')
    llm.answer = json.dumps({'proposals': candidates}, ensure_ascii=False)
    response = client.post(path(chat)+'/memory/proposals',
        json={**versions(memory(client, chat)), 'source_message_id': source['id']})
    assert response.status_code == 200, response.text
    return response.json()['workspace']


def resolve(client, chat, proposal, action='accept'):
    return client.post(path(chat)+'/memory/proposals/'+proposal['id'],
        json={**versions(memory(client, chat)), 'action': action})


def test_three_layers_are_sent_and_original_history_is_retained(rig):
    client, llm = rig
    chat = create(client, problem={'statement': 'Найти пару с заданной суммой'})
    save(client, chat, value='Линейное время')
    save(client, chat, 'long_term', 'язык', 'Использовать Python')
    assert run(client, chat, 'Ранний вопрос').status_code == 200
    assert run(client, chat, 'Средний вопрос').status_code == 200
    result = run(client, chat, 'Последний вопрос').json()
    sent = llm.calls[-1]
    assert len(sent) == 7  # system + profile + working + long-term + N=2 + current
    assert 'Линейное время' in sent[2].content
    assert 'Найти пару' in sent[2].content
    assert 'Использовать Python' in sent[3].content
    assert [m.content for m in sent[4:]] == ['Средний вопрос', llm.answer, 'Последний вопрос']
    assert result['run']['estimate']['context_mode'] == 'memory_layers'
    assert result['run']['estimate']['working_memory_tokens'] > 0
    assert len(result['run']['memory_context']['message_ids']) == 2
    assert len(client.get(path(chat)).json()['messages']) == 6
    assert memory(client, chat)['proposals'] == []  # никаких автоматических записей


def test_tasks_share_only_long_term_and_delete_preserves_it(rig):
    client, _ = rig
    first, second = create(client), create(client)
    run(client, first, 'Сохранить знание')
    source = memory(client, first)['short_term'][0]['id']
    save(client, first, value='План только первой задачи')
    saved = save(client, first, 'long_term', 'знание', 'Хеш-таблица даёт быстрый поиск', source_message_id=source)
    assert memory(client, second)['working'] == []
    assert memory(client, second)['long_term'] == saved['long_term']
    assert client.get(path(first).replace('algorithm_coach', 'dialogue')+'/memory').status_code == 422
    foreign = client.post('/api/v1/agents/dialogue/conversations', json={}).json()['id']
    assert client.get(path(foreign)+'/memory').status_code == 404
    assert client.delete(path(first)).status_code == 204
    retained = memory(client, second)['long_term'][0]
    assert retained['source_message_id'] == source and 'Сохранить знание' in retained['source_excerpt']
    assert client.get(path(first)+'/memory').status_code == 404


def test_proposals_do_not_change_memory_until_each_confirmation(rig):
    client, llm = rig
    chat = create(client)
    run(client, chat, 'Цель — изучить поиск. Я предпочитаю Python.')
    workspace = propose(client, llm, chat, [
        {'layer': 'working', 'key': 'goal', 'value': 'Изучить поиск'},
        {'layer': 'long_term', 'key': 'язык', 'value': 'Python'}])
    assert workspace['working'] == workspace['long_term'] == []
    llm.answer = 'Проверка'
    run(client, chat)
    assert json.loads(llm.calls[-1][2].content.split('\n', 1)[1])['entries'] == {}
    for proposal in workspace['proposals']:
        assert resolve(client, chat, proposal).status_code == 200
    saved = memory(client, chat)
    assert saved['working'][0]['author'] == saved['long_term'][0]['author'] == 'llm_confirmed'
    assert resolve(client, chat, workspace['proposals'][0]).status_code == 409
    proposal_runs = [r for r in client.get(path(chat)).json()['runs'] if r['purpose'] == 'memory_proposals']
    assert len(proposal_runs) == 1 and proposal_runs[0]['usage']['total_tokens'] == 120


def test_reject_stale_proposal_and_stale_write(rig):
    client, llm = rig
    chat = create(client)
    run(client, chat)
    proposal = propose(client, llm, chat, [{'layer': 'working', 'key': 'goal', 'value': 'LLM цель'}])['proposals'][0]
    old = versions(memory(client, chat))
    save(client, chat, value='Ручная цель')
    stale = client.post(path(chat)+'/memory/entries', json={**old, 'layer': 'working', 'key': 'goal', 'value': 'Старая правка'})
    assert stale.status_code == 409
    assert resolve(client, chat, proposal).json()['code'] == 'stale_proposal'
    assert resolve(client, chat, proposal, 'reject').status_code == 200
    assert memory(client, chat)['working'][0]['value'] == 'Ручная цель'


@pytest.mark.parametrize('answer', ['not json', '{"proposals":[{"layer":"system","key":"goal","value":"x"}]}', '', '{"proposals":[{"layer":"working","key":"unknown","value":"x"}]}', '{"proposals":[{"layer":"working","key":"goal","value":"x"},{"layer":"working","key":"goal","value":"y"}]}'])
def test_invalid_proposals_keep_memory_and_account_for_usage(rig, answer):
    client, llm = rig
    chat = create(client)
    save(client, chat)
    run(client, chat)
    before = memory(client, chat)
    llm.answer = answer
    response = client.post(path(chat)+'/memory/proposals',
        json={**versions(before), 'source_message_id': before['short_term'][0]['id']})
    assert response.status_code == 502
    assert memory(client, chat)['working'] == before['working']
    assert memory(client, chat)['proposals'] == []
    last = client.get(path(chat)).json()['runs'][-1]
    assert last['purpose'] == 'memory_proposals' and last['status'] == 'error'
    assert last['usage']['total_tokens'] == 120


def test_preview_is_read_only_and_disabled_memory_is_excluded(rig):
    client, llm = rig
    chat = create(client)
    run(client, chat, 'Я знаю Python')
    source = memory(client, chat)['short_term'][0]['id']
    save(client, chat, 'long_term', 'знание', 'Python', source_message_id=source)
    save(client, chat, 'long_term', 'знание', 'Python', active=False)
    before = memory(client, chat)
    assert before['long_term'][0]['source_message_id'] == source
    count = len(llm.calls)
    for _ in range(2):
        preview = client.post(BASE+'/preview', json={'conversation_id': chat, 'message': 'Черновик'})
        assert preview.status_code == 200, preview.text
    assert memory(client, chat) == before and len(llm.calls) == count
    run(client, chat)
    assert json.loads(llm.calls[-1][3].content.split('\n', 1)[1]) == {}
    assert client.get(path(chat)).json()['runs'][-1]['memory_context']['long_term'] == []
    propose(client, llm, chat, [])
    assert json.loads(llm.calls[-1][-1].content)['existing_long_term'] == {}


def test_invalid_source_and_schema_do_not_write(rig):
    client, llm = rig
    chat, other = create(client), create(client)
    run(client, chat)
    run(client, other)
    original = memory(client, chat)
    for source in [original['short_term'][-1]['id'], memory(client, other)['short_term'][0]['id']]:
        response = client.post(path(chat)+'/memory/proposals', json={**versions(original), 'source_message_id': source})
        assert response.status_code == 422
    for field, value in [('value', '  '), ('layer', 'system')]:
        payload = {**versions(original), 'layer': 'working', 'key': 'goal', 'value': 'x', field: value}
        assert client.post(path(chat)+'/memory/entries', json=payload).status_code == 422
    assert client.put(path(chat)+'/memory/problem', json={**versions(original), 'problem': {'evil': 'x'}}).status_code == 422
    assert len(llm.calls) == 2
    assert memory(client, chat) == original


def test_provider_error_preserves_question_and_unknown_usage(rig):
    client, llm = rig
    chat = create(client)
    llm.error = AgentError('llm_timeout', 'Таймаут провайдера', 504)
    assert run(client, chat, 'Важный вопрос').status_code == 504
    restored = client.get(path(chat)).json()
    assert restored['messages'] == [{'role': 'user', 'content': 'Важный вопрос'}]
    assert restored['runs'][0]['status'] == 'error'
    assert restored['runs'][0]['usage'] is None


def test_changed_memory_during_generation_rejects_stale_reply(rig):
    client, llm = rig
    chat = create(client)
    agent = client.app.state.registry.get('algorithm_coach')
    async def change():
        from capabilities.memory_layers.models import SaveMemory
        workspace = await agent.memory.repository.workspace('algorithm_coach', chat)
        await agent.memory.save('algorithm_coach', chat, SaveMemory(
            **agent.memory.repository.versions(workspace).model_dump(), layer='working', key='goal', value='Изменённая цель'))
    llm.before_return = change
    assert run(client, chat).status_code == 409
    detail = client.get(path(chat)).json()
    assert len(detail['messages']) == 1
    assert detail['runs'][0]['usage']['total_tokens'] == 120


def test_restart_and_non_destructive_migration(tmp_path):
    database = tmp_path/'restart.sqlite3'
    settings = Settings(mode='demo', database_path=database, _env_file=None)
    with TestClient(create_app(settings)) as client:
        old = client.post('/api/v1/agents/dialogue/conversations', json={}).json()['id']
        chat = create(client, problem={'statement': 'Two Sum'})
        save(client, chat, 'long_term', 'знание', 'Хеширование')
        run(client, chat)
        before = memory(client, chat)
    with TestClient(create_app(settings)) as client:
        assert memory(client, chat) == before
        assert client.get('/api/v1/agents/dialogue/conversations/'+old).status_code == 200
        assert client.get(path(chat)).json()['runs'][0]['memory_context']['problem']['statement'] == 'Two Sum'
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT count(*) FROM schema_migrations').fetchone()[0] == 3
        # Дополнительный профиль не видит общую память первого профиля.
        db.execute("INSERT INTO profiles(id,name) VALUES('other','Другой профиль')")
        db.execute("UPDATE tasks SET profile_id='other' WHERE conversation_id=?", (chat,))
    with TestClient(create_app(settings)) as client:
        assert memory(client, chat)['long_term'] == []


def test_invalid_creation_does_not_leave_orphan_conversation(rig):
    client, _ = rig
    for body in [{'problem': {'unknown': 'x'}}, {'context_settings': {'mode': 'full'}}]:
        assert client.post(BASE+'/conversations', json=body).status_code == 422
    assert client.get(BASE+'/conversations').json() == []


async def test_upgrade_existing_day10_database_preserves_messages(tmp_path):
    from infrastructure.sqlite_store import SqliteConversationStore
    from infrastructure.sqlite_memory_layers import SqliteMemoryRepository
    database = tmp_path/'day10.sqlite3'
    store = SqliteConversationStore(database)
    await store.initialize()
    chat = await store.create('dialogue', 'Старый диалог')
    await store.append_message('dialogue', chat.id, 'user', 'Старое сообщение')
    with sqlite3.connect(database) as db:
        assert not db.execute("SELECT name FROM sqlite_master WHERE name='tasks'").fetchall()
    repo = SqliteMemoryRepository(store)
    await repo.initialize()
    await repo.initialize()
    restored = await store.get('dialogue', chat.id)
    assert restored.messages[0].content == 'Старое сообщение'
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT count(*) FROM schema_migrations').fetchone()[0] == 3
