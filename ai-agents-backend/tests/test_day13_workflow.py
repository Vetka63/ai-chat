"""Сквозная проверка сохранённого автомата: фазы, шаг и пауза."""
from fastapi.testclient import TestClient

from agent_core.models import Completion
from application.main import create_app
from application.settings import Settings
from capabilities.task_workflow.models import WorkflowCommand

BASE = '/api/v1/agents/algorithm_coach'


def chat(client):
    response = client.post(BASE + '/conversations', json={
        'title': 'Two Sum', 'problem': {'statement': 'Найти индексы двух чисел с суммой target'},
        'context_settings': {'mode': 'sliding_window', 'keep_last': 4}})
    assert response.status_code == 201, response.text
    return response.json()['id']


def workflow(client, chat_id):
    return client.get(f'{BASE}/conversations/{chat_id}/workflow').json()


def action(client, chat_id, name, **details):
    return client.post(f'{BASE}/conversations/{chat_id}/workflow/actions', json={
        'action': name, 'expected_revision': workflow(client, chat_id)['state']['revision'], **details})


def run(client, chat_id, message):
    return client.post(BASE + '/runs', json={'conversation_id': chat_id, 'message': message})


def test_two_sum_workflow_is_persistent_and_sequential(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day13.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat_id = chat(client)
        assert workflow(client, chat_id)['state']['phase'] == 'planning'
        assert action(client, chat_id, 'pause').json()['state']['status'] == 'paused'
        assert run(client, chat_id, 'План').status_code == 409
        assert action(client, chat_id, 'resume').json()['state']['phase'] == 'planning'
        assert action(client, chat_id, 'accept_solution', content={'text': 'code'}).status_code == 409
        assert action(client, chat_id, 'accept_plan', content={'steps': ['Подобрать алгоритм']}).status_code == 409
        assert run(client, chat_id, 'Составь план Two Sum').status_code == 200
        result = action(client, chat_id, 'accept_plan', content={'steps': ['Подобрать подход', 'Написать код']})
        assert result.status_code == 200, result.text
        state = result.json()['state']
        assert state['phase'] == 'execution' and state['expected_action'] == 'work_on_step'
        assert state['current_step_id'] == result.json()['artifacts'][0]['content']['steps'][0]['id']
        assert action(client, chat_id, 'select_step', step_id='no-such-step').status_code == 409
        assert action(client, chat_id, 'pause').json()['state']['status'] == 'paused'
        assert run(client, chat_id, 'Продолжай').status_code == 409
        assert action(client, chat_id, 'accept_solution', content={'text': 'code'}).status_code == 409
    with TestClient(create_app(settings)) as client:
        state = workflow(client, chat_id)['state']
        assert state['phase'] == 'execution' and state['status'] == 'paused'
        assert action(client, chat_id, 'resume').json()['state']['current_step_id'] == state['current_step_id']
        second = workflow(client, chat_id)['artifacts'][0]['content']['steps'][1]['id']
        assert action(client, chat_id, 'select_step', step_id=second).json()['state']['current_step_id'] == second
        assert run(client, chat_id, 'Напиши код').status_code == 200
        assert action(client, chat_id, 'accept_solution', content={'text': 'def two_sum(nums, target): return []'}).json()['state']['phase'] == 'validation'
        assert action(client, chat_id, 'pause').json()['state']['status'] == 'paused'
    with TestClient(create_app(settings)) as client:
        assert workflow(client, chat_id)['state']['phase'] == 'validation'
        assert action(client, chat_id, 'resume').status_code == 200
        assert run(client, chat_id, 'Проверь сохранённое решение').status_code == 200
        result = action(client, chat_id, 'accept_validation', content={'text': 'Нужны тесты', 'method': 'llm_review'})
        assert result.json()['state']['phase'] == 'done'
        assert [a['kind'] for a in result.json()['artifacts']] == ['plan', 'solution', 'validation']
        assert run(client, chat_id, 'Продолжай').status_code == 409
        assert action(client, chat_id, 'pause').status_code == 409


def test_prompt_uses_persisted_phase_and_late_reply_cannot_override_pause(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day13.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat_id = chat(client)
        coach = client.app.state.registry.get('algorithm_coach')
        calls = []

        class PauseDuringAnswer:
            async def complete(self, messages, *, model, temperature, max_tokens):
                calls.append(messages)
                if len(calls) == 2:
                    snapshot = await coach.workflow.workspace('algorithm_coach', chat_id)
                    await client.app.state.workflow.apply('algorithm_coach', chat_id,
                        WorkflowCommand(action='pause', expected_revision=snapshot.state.revision))
                return Completion(content='Ответ', model=model)

        coach.calls.llm = PauseDuringAnswer()
        assert run(client, chat_id, 'Предложи план').status_code == 200
        assert action(client, chat_id, 'accept_plan', content={'steps': ['Словарь', 'Цикл']}).status_code == 200
        response = run(client, chat_id, 'Напиши функцию')
        assert response.status_code == 409
        assert workflow(client, chat_id)['state']['status'] == 'paused'
        assert [m['role'] for m in client.get(f'{BASE}/conversations/{chat_id}').json()['messages']] == [
            'user', 'assistant', 'user']
        assert '"phase": "execution"' in calls[-1][2].content
        assert 'Словарь' in calls[-1][2].content
        assert action(client, chat_id, 'resume').status_code == 200
        assert run(client, chat_id, 'Продолжи выбранный шаг').status_code == 200


def test_new_message_invalidates_old_candidate_and_versions_block_stale_action(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path / 'day13.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat_id = chat(client)
        assert run(client, chat_id, 'Первый план').status_code == 200
        old = workflow(client, chat_id)
        assert old['state']['candidate_message_id'] is not None
        assert action(client, chat_id, 'pause').status_code == 200
        stale = client.post(f'{BASE}/conversations/{chat_id}/workflow/actions', json={
            'action': 'accept_plan', 'expected_revision': old['state']['revision'],
            'content': {'steps': ['Нельзя принять']}})
        assert stale.status_code == 409 and stale.json()['code'] == 'state_conflict'
        assert action(client, chat_id, 'resume').status_code == 200
        assert run(client, chat_id, 'Доработай план').status_code == 200
        assert workflow(client, chat_id)['state']['candidate_message_id'] != old['state']['candidate_message_id']
