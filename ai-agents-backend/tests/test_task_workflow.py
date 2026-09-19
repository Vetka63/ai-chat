"""День 13: граф, паузы, версии, replay и восстановление без повторных LLM-вызовов."""
import json
import sqlite3
from uuid import uuid4
import pytest
from fastapi.testclient import TestClient
from application.main import create_app
from application.settings import Settings
from agent_core.models import AgentError
from capabilities.task_workflow.models import TransitionCommand
from test_memory_layers import rig, create, path, memory, run, BASE, ObservedLlm


def flow(client, chat):
    response = client.get(path(chat)+'/task')
    assert response.status_code == 200, response.text
    return response.json()


def change(client, chat, resource='events', **body):
    return client.post(path(chat)+'/task/'+resource, json={
        'command_id': str(uuid4()), 'expected_revision': flow(client, chat)['state']['revision'], **body})


def event(client, chat, name):
    result = change(client, chat, event=name)
    assert result.status_code == 200, result.text
    return result.json()


def plan(client, chat):
    result = change(client, chat, 'artifacts', kind='plan', content={'steps': [{'title': 'Построить словарь'}, {'title': 'Проверить дубликаты'}]})
    assert result.status_code == 200, result.text
    return result.json()


def test_graph_and_server_owned_state(rig):
    client, llm = rig
    chat = create(client)
    assert flow(client, chat)['state']['expected_action'] == 'save_plan'
    assert change(client, chat, event='finish').json()['code'] == 'transition_not_allowed'
    assert change(client, chat, event='teleport').status_code == 422
    event(client, chat, 'start_execution')
    event(client, chat, 'start_validation')
    event(client, chat, 'request_changes')
    event(client, chat, 'request_replan')
    assert flow(client, chat)['state']['phase'] == 'planning'
    assert not llm.calls


@pytest.mark.parametrize('phase', ['planning', 'execution', 'validation'])
def test_pause_resume_preserves_exact_step_and_expectation(rig, phase):
    client, llm = rig
    chat = create(client)
    data = plan(client, chat)
    step = data['artifacts'][0]['content']['steps'][1]['id']
    assert change(client, chat, 'step', step_id=step).status_code == 200
    if phase != 'planning': event(client, chat, 'start_execution')
    if phase == 'validation': event(client, chat, 'start_validation')
    before = flow(client, chat)['state']
    paused = event(client, chat, 'pause')['state']
    assert paused['phase'] == phase and paused['current_step_id'] == step
    assert paused['expected_action'] == before['expected_action']
    assert run(client, chat).json()['code'] == 'task_paused'
    assert not client.get(path(chat)).json()['messages'] and not llm.calls
    assert change(client, chat, 'step', step_id=step).json()['code'] == 'task_paused'
    resumed = event(client, chat, 'resume')['state']
    assert resumed['status'] == 'active' and resumed['current_step_id'] == step
    assert run(client, chat).status_code == 200
    context = json.loads(llm.calls[-1][2].content.split('\n', 1)[1])['workflow']
    assert context['state']['phase'] == phase
    assert context['state']['current_step_id'] == step
    assert context['artifacts']['plan']['content']['steps'][1]['title'] == 'Проверить дубликаты'


def test_artifact_versions_sources_and_validation_method(rig):
    client, _ = rig
    chat = create(client)
    first = plan(client, chat)
    steps = first['artifacts'][0]['content']['steps']
    second = change(client, chat, 'artifacts', kind='plan', content={'steps': steps}).json()
    assert second['artifacts'][-1]['revision'] == 2
    assert second['artifacts'][0] == first['artifacts'][0]
    event(client, chat, 'start_execution')
    assert change(client, chat, 'artifacts', kind='solution', content={'text': 'return []'}).status_code == 200
    event(client, chat, 'start_validation')
    assert change(client, chat, 'artifacts', kind='validation', content={'text': 'Проверка'}).status_code == 422
    report = change(client, chat, 'artifacts', kind='validation', content={'text': 'Дубликаты не обработаны', 'method': 'llm_review'}).json()['artifacts'][-1]
    assert report['plan_revision'] == 2 and report['solution_revision'] == 1
    assert report['content']['method'] == 'llm_review'
    assert change(client, chat, 'artifacts', kind='solution', content={'text': 'bad phase'}).status_code == 409
    event(client, chat, 'finish')
    assert flow(client, chat)['allowed_events'] == []
    assert run(client, chat).json()['code'] == 'task_done'


def test_command_replay_conflicts_and_stale_revision(rig):
    client, _ = rig
    chat = create(client)
    body = {'command_id': 'pause-once', 'expected_revision': 1, 'event': 'pause'}
    first = client.post(path(chat)+'/task/events', json=body)
    assert client.post(path(chat)+'/task/events', json=body).json() == first.json()
    assert len(flow(client, chat)['events']) == 1
    assert client.post(path(chat)+'/task/events', json={**body, 'event': 'resume'}).json()['code'] == 'command_conflict'
    assert change(client, chat, event='resume', expected_revision=1).json()['code'] == 'state_conflict'


def test_run_replay_returns_same_result_without_duplicate_usage_or_messages(rig):
    client, llm = rig
    chat = create(client)
    body = {'conversation_id': chat, 'message': 'Объясни задачу', 'command_id': 'run-once', 'expected_revision': 1}
    first = client.post(BASE+'/runs', json=body)
    assert first.status_code == 200, first.text
    assert client.post(BASE+'/runs', json=body).json() == first.json()
    stored = client.get(path(chat)).json()
    assert len(stored['messages']) == 2 and len(stored['runs']) == 1 and len(llm.calls) == 1
    assert stored['runs'][0] == first.json()['run']
    assert client.post(BASE+'/runs', json={**body, 'message': 'Другой запрос'}).json()['code'] == 'command_conflict'


def test_late_response_after_pause_is_not_published_but_usage_remains(rig):
    client, llm = rig
    chat = create(client)
    agent = client.app.state.registry.get('algorithm_coach')
    async def pause():
        await agent.workflow.change('algorithm_coach', chat, 'transition', TransitionCommand(command_id='during-run', expected_revision=1, event='pause'))
    llm.before_return = pause
    result = run(client, chat)
    assert result.status_code == 409 and result.json()['code'] == 'state_conflict'
    stored = client.get(path(chat)).json()
    assert [m['role'] for m in stored['messages']] == ['user']
    assert stored['runs'][0]['usage']['total_tokens'] == 120
    assert stored['runs'][0]['status'] == 'error'
    assert flow(client, chat)['state']['status'] == 'paused'
    assert flow(client, chat)['active_command_id'] is None


def test_error_does_not_advance_and_same_command_is_not_retried(rig):
    client, llm = rig
    chat = create(client)
    llm.error = AgentError('upstream', 'Сбой провайдера', 502)
    body = {'conversation_id': chat, 'message': 'Вопрос', 'command_id': 'failed-run'}
    assert client.post(BASE+'/runs', json=body).status_code == 502
    assert client.post(BASE+'/runs', json=body).json()['code'] == 'command_error'
    assert len(llm.calls) == 1 and flow(client, chat)['state']['revision'] == 1
    assert flow(client, chat)['active_command_id'] is None


def test_isolation_and_invalid_artifacts(rig):
    client, _ = rig
    first, second = create(client), create(client)
    data = plan(client, first)
    step = data['state']['current_step_id']
    assert change(client, second, 'step', step_id=step).status_code == 422
    assert change(client, second, 'artifacts', kind='plan', content={'steps': [{'id': step, 'title': 'Чужой'}]}).status_code == 422
    assert change(client, second, 'artifacts', kind='plan', content={'steps': [{'title': '  '}]}).status_code == 422
    assert change(client, second, 'artifacts', kind='plan', content={'steps': [{'title': 'Новый'}]}, source_message_id=999999).status_code == 404
    assert flow(client, second)['artifacts'] == []
    assert client.get(path(first).replace('algorithm_coach', 'dialogue')+'/task').status_code == 422


def test_restart_preserves_artifacts_and_marks_pending_command_interrupted(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path/'restart.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat = create(client)
        plan(client, chat)
        event(client, chat, 'start_execution')
        event(client, chat, 'pause')
        before = flow(client, chat)
    with sqlite3.connect(settings.database_path) as db:
        db.execute('INSERT INTO task_commands VALUES(?,?,?,?,?,?)', (before['task_id'], 'lost', 'fp', 'pending', None, 'lost-run'))
    with TestClient(create_app(settings)) as client:
        assert flow(client, chat) == before
        assert client.get(path(chat)).json()['messages'] == []
        event(client, chat, 'resume')
        assert flow(client, chat)['state']['phase'] == 'execution'
    with sqlite3.connect(settings.database_path) as db:
        assert db.execute("SELECT status FROM task_commands WHERE command_id='lost'").fetchone()[0] == 'interrupted'


def test_upgrade_day12_preserves_task_and_profile(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path/'legacy.sqlite3', _env_file=None)
    with TestClient(create_app(settings)) as client:
        chat = create(client, profile_id='beginner', problem={'statement': 'Старое условие'})
    with sqlite3.connect(settings.database_path) as db:
        for table in ('task_commands', 'task_events', 'task_artifacts', 'task_states'):
            db.execute('DROP TABLE '+table)
        db.execute("DELETE FROM schema_migrations WHERE version='013_task_workflow'")
    with TestClient(create_app(settings)) as client:
        state = memory(client, chat)
        assert state['profile']['id'] == 'beginner' and state['task']['problem']['statement'] == 'Старое условие'
        assert state['workflow']['state']['phase'] == 'planning'


def test_result_transaction_rolls_back_reply_and_event_on_failure(rig, monkeypatch):
    client, llm = rig
    chat = create(client)
    repository = client.app.state.registry.get('algorithm_coach').workflow.repository
    async def broken_event(*args):
        raise RuntimeError('simulated database failure')
    monkeypatch.setattr(repository, 'event', broken_event)
    with pytest.raises(RuntimeError, match='simulated database failure'):
        run(client, chat)
    saved = client.get(path(chat)).json()
    assert [m['role'] for m in saved['messages']] == ['user']
    assert saved['runs'][0]['status'] == 'error' and saved['runs'][0]['usage']['total_tokens'] == 120
    assert flow(client, chat)['events'] == [] and flow(client, chat)['active_command_id'] is None


def test_pause_then_resume_still_rejects_old_result(rig):
    client, llm = rig
    chat = create(client)
    workflow = client.app.state.registry.get('algorithm_coach').workflow
    async def toggle():
        await workflow.change('algorithm_coach', chat, 'transition', TransitionCommand(command_id='p', expected_revision=1, event='pause'))
        await workflow.change('algorithm_coach', chat, 'transition', TransitionCommand(command_id='r', expected_revision=2, event='resume'))
    llm.before_return = toggle
    assert run(client, chat).json()['code'] == 'state_conflict'
    assert flow(client, chat)['state']['status'] == 'active'
    assert len(client.get(path(chat)).json()['messages']) == 1


def test_database_reservation_blocks_another_worker(rig):
    client, llm = rig
    chat = create(client)
    data = flow(client, chat)
    database = client.app.state.store.path
    with sqlite3.connect(database) as db:
        db.execute('INSERT INTO task_commands VALUES(?,?,?,?,?,?)', (data['task_id'], 'other-worker', 'fp', 'pending', None, 'run'))
    assert run(client, chat).json()['code'] == 'task_busy'
    assert change(client, chat, event='start_execution').json()['code'] == 'task_busy'
    assert not llm.calls and not client.get(path(chat)).json()['messages']
    assert event(client, chat, 'pause')['state']['status'] == 'paused'
