"""Сквозные проверки правил без платных API-вызовов."""
import json
import sqlite3

from fastapi.testclient import TestClient

from agent_core.models import Completion
from application.main import create_app
from application.settings import Settings


BASE = '/api/v1/agents/algorithm_coach'


class FakeLlm:
    def __init__(self):
        self.reply = '## Шаги\n1. Найти дополнение через словарь'
        self.bad_judge = False
        self.main_calls = 0
        self.judge_calls = []
        self.judge_models = []

    async def complete(self, messages, *, model, temperature, max_tokens):
        if 'независимый проверяющий' in messages[0].content:
            payload = json.loads(messages[1].content)
            self.judge_calls.append(payload)
            self.judge_models.append(model)
            if self.bad_judge:
                return Completion(content='не JSON', model=model)
            conflict = 'JavaScript' in payload['candidate'] or 'javascript' in payload['candidate']
            return Completion(content=json.dumps({'checks': [
                {'rule_id': rule['id'], 'verdict': 'conflict' if conflict else 'pass',
                 'reason': 'Запрошен или предложен JavaScript' if conflict else 'Правило соблюдено'}
                for rule in payload['rules']]}), model=model)
        self.main_calls += 1
        return Completion(content=self.reply, model=model)


def create(client):
    response = client.post(BASE + '/conversations', json={
        'title': 'Two Sum', 'problem': {'statement': 'Найти индексы двух чисел с суммой target'},
        'context_settings': {'mode': 'sliding_window', 'keep_last': 4}})
    assert response.status_code == 201, response.text
    return response.json()['id']


def rules(client, chat):
    return client.get(f'{BASE}/conversations/{chat}/invariants').json()


def put_rules(client, chat, entries, revision=None):
    return client.put(f'{BASE}/conversations/{chat}/invariants', json={
        'expected_revision': revision or rules(client, chat)['revision'], 'rules': entries})


def flow(client, chat):
    return client.get(f'{BASE}/conversations/{chat}/workflow').json()


def action(client, chat, name, content):
    return client.post(f'{BASE}/conversations/{chat}/workflow/actions', json={
        'action': name, 'expected_revision': flow(client, chat)['state']['revision'], 'content': content})


def run(client, chat, message):
    return client.post(BASE + '/runs', json={'conversation_id': chat, 'message': message})


def test_rules_are_task_scoped_persistent_and_input_conflicts_become_explained_refusals(tmp_path):
    database = tmp_path / 'day14.sqlite3'
    settings = Settings(mode='demo', database_path=database, _env_file=None)
    with TestClient(create_app(settings)) as client:
        first, second = create(client), create(client)
        fake = FakeLlm()
        client.app.state.registry.get('algorithm_coach').calls.llm = fake
        rule = {'kind': 'language', 'label': 'Язык решения', 'value': 'Python', 'active': True}
        saved = put_rules(client, first, [rule])
        assert saved.status_code == 200, saved.text
        assert saved.json()['revision'] == 2
        assert rules(client, second)['rules'] == []
        stale = put_rules(client, first, [rule], revision=1)
        assert stale.status_code == 409 and stale.json()['code'] == 'state_conflict'

        response = run(client, first, 'Сделай решение на JavaScript')
        assert response.status_code == 200, response.text
        assert response.json()['source'] == 'policy'
        assert 'Язык решения' in response.json()['reply']
        assert fake.main_calls == 0
        assert fake.judge_calls[-1]['stage'] == 'input'
        assert fake.judge_models[-1] == 'deepseek-v4-pro'
        assert fake.judge_calls[-1]['profile']['id'] == 'local'
        assert flow(client, first)['state']['candidate_message_id'] is None
        assert rules(client, first)['checks'][-1]['verdict'] == 'conflict'
        history = client.get(f'{BASE}/conversations/{first}').json()['messages']
        assert [item['role'] for item in history] == ['user', 'assistant']
        assert 'JavaScript' in history[0]['content']
    with TestClient(create_app(settings)) as client:
        assert rules(client, first)['rules'][0]['value'] == 'Python'
        assert rules(client, second)['rules'] == []
    with sqlite3.connect(database) as db:
        assert db.execute('SELECT count(*) FROM task_invariant_rules').fetchone()[0] == 1
        assert db.execute('SELECT count(*) FROM task_invariant_checks').fetchone()[0] == 1


def test_output_and_edited_artifact_are_checked_before_publication_or_transition(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day14.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat = create(client)
        fake = FakeLlm()
        client.app.state.registry.get('algorithm_coach').calls.llm = fake
        assert put_rules(client, chat, [{'kind': 'language', 'label': 'Только Python', 'value': 'Python'}]).status_code == 200
        good = run(client, chat, 'Составь план')
        assert good.status_code == 200 and good.json()['source'] != 'policy'
        assert action(client, chat, 'accept_plan', {'steps': ['Написать функцию']}).status_code == 200
        assert put_rules(client, chat, []).status_code == 409

        fake.reply = '```javascript\nfunction twoSum() {}\n```'
        bad = run(client, chat, 'Напиши решение')
        assert bad.status_code == 200 and bad.json()['source'] == 'policy'
        assert 'Только Python' in bad.json()['reply']
        assert flow(client, chat)['state']['candidate_message_id'] is None

        fake.reply = '```python\ndef two_sum(nums, target): return []\n```'
        assert run(client, chat, 'Предложи решение на Python').status_code == 200
        bypass = action(client, chat, 'accept_solution', {'text': '```javascript\nfunction twoSum() {}\n```'})
        assert bypass.status_code == 422 and bypass.json()['code'] == 'invariant_conflict'
        assert flow(client, chat)['state']['phase'] == 'execution'
        accepted = action(client, chat, 'accept_solution', {'text': fake.reply})
        assert accepted.status_code == 200, accepted.text
        assert accepted.json()['state']['phase'] == 'validation'
        assert {check['stage'] for check in rules(client, chat)['checks']} == {'input', 'output', 'artifact'}


def test_invalid_judge_is_fail_closed_without_main_call(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day14.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat = create(client)
        fake = FakeLlm()
        fake.bad_judge = True
        client.app.state.registry.get('algorithm_coach').calls.llm = fake
        assert put_rules(client, chat, [{'label': 'Без сортировки', 'value': 'Не использовать сортировку'}]).status_code == 200
        response = run(client, chat, 'Составь план')
        assert response.status_code == 200 and response.json()['source'] == 'policy'
        assert 'Не удалось подтвердить' in response.json()['reply']
        assert fake.main_calls == 0
        assert rules(client, chat)['checks'][-1]['verdict'] == 'uncertain'


def test_rule_change_invalidates_old_candidate_and_pause_blocks_edit(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day14.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat = create(client)
        first = run(client, chat, 'Предложи план')
        assert first.status_code == 200
        old = flow(client, chat)['state']
        assert old['candidate_message_id'] is not None
        saved = put_rules(client, chat, [{'label': 'Без сортировки', 'value': 'Не использовать сортировку'}])
        assert saved.status_code == 200
        assert flow(client, chat)['state']['candidate_message_id'] is None
        stale = client.post(f'{BASE}/conversations/{chat}/workflow/actions', json={
            'action': 'accept_plan', 'expected_revision': old['revision'],
            'content': {'steps': ['Старый план']}})
        assert stale.status_code == 409
        current = flow(client, chat)['state']['revision']
        paused = client.post(f'{BASE}/conversations/{chat}/workflow/actions', json={
            'action': 'pause', 'expected_revision': current})
        assert paused.status_code == 200
        assert put_rules(client, chat, []).status_code == 409
