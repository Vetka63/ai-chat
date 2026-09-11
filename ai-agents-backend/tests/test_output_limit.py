"""Лимит ответа опционален, изолирован от summary и сохраняется между запусками."""
import json
import httpx
import pytest
from pydantic import ValidationError
from agent_core.models import AgentCommand, PreviewCommand, Message, AgentError
from agents.dialogue.config import DialogueAgentConfig
from infrastructure.completions import ChatCompletionsClient
from infrastructure.sqlite_store import SqliteConversationStore
from test_context_memory import build
from fastapi.testclient import TestClient
from application.main import create_app
from application.settings import Settings


@pytest.mark.parametrize('provider', ['deepseek', 'mistral'])
@pytest.mark.parametrize('limit', [None, 1200, 9000])
async def test_provider_payload_omits_unset_limit(provider, limit):
    captured = {}
    def handler(request):
        captured.update(json.loads(request.content))
        return httpx.Response(200, json={'model': 'test', 'choices': [{'message': {'content': 'ok'}, 'finish_reason': 'stop'}]})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        await ChatCompletionsClient(http, 'test', provider, 'https://test.invalid').complete(
            [Message(role='user', content='hi')], model='test', temperature=.7, max_tokens=limit)
    assert ('max_tokens' in captured) == (limit is not None)
    if limit is not None:
        assert captured['max_tokens'] == limit


@pytest.mark.parametrize('value', [0, -1, 1.5, True, '1200'])
def test_invalid_limit_rejected(value):
    for cls, kwargs in [(AgentCommand, {'conversation_id': 'test', 'message': 'hi'}), (PreviewCommand, {})]:
        with pytest.raises(ValidationError):
            cls(**kwargs, max_output_tokens=value)


async def test_default_override_reset_and_persistence(tmp_path):
    agent, llm, store, summaries, usage, conv = await build(tmp_path, pairs=0)
    assert DialogueAgentConfig(model='test', system_prompt='test').max_tokens is None
    assert DialogueAgentConfig(model='test', system_prompt='test', max_tokens=9000).max_tokens == 9000
    preview = await agent.preview(PreviewCommand(conversation_id=conv.id, message='hi'))
    assert preview.reserved_output_tokens is None
    assert preview.occupancy_percent == round(100 * preview.prompt_tokens / preview.context_window, 2)
    await agent.run(AgentCommand(conversation_id=conv.id, message='hi'))
    assert llm.calls[-1][2]['max_tokens'] is None
    await agent.run(AgentCommand(conversation_id=conv.id, message='test', max_output_tokens=1200))
    assert llm.calls[-1][2]['max_tokens'] == 1200
    restarted = SqliteConversationStore(store.path)
    await restarted.initialize()
    assert (await restarted.get('dialogue', conv.id)).max_output_tokens == 1200
    preview = await agent.preview(PreviewCommand(conversation_id=conv.id, message='again'))
    assert preview.reserved_output_tokens == 1200
    assert (await agent.preview(PreviewCommand(conversation_id=conv.id, max_output_tokens=None))).reserved_output_tokens is None
    await agent.run(AgentCommand(conversation_id=conv.id, message='reset', max_output_tokens=None))
    assert llm.calls[-1][2]['max_tokens'] is None
    assert (await store.get('dialogue', conv.id)).max_output_tokens is None
    assert [r.estimate.reserved_output_tokens for r in await usage.list('dialogue', conv.id)] == [None, 1200, None]


@pytest.mark.parametrize('summary_limit', [None, 1200])
async def test_summary_has_independent_limit(tmp_path, summary_limit):
    agent, llm, store, summaries, usage, conv = await build(tmp_path)
    agent.memory.summarizer.max_tokens = summary_limit
    await agent.run(AgentCommand(conversation_id=conv.id, message='hi', max_output_tokens=9000))
    assert [(call[0], call[2]['max_tokens']) for call in llm.calls] == [(True, summary_limit), (False, 9000)]
    source = await store.get('dialogue', conv.id)
    copy = await store.fork(source, source.context_settings)
    assert (await store.get('dialogue', copy.id)).max_output_tokens == 9000


async def test_limit_above_model_max_rejected_before_llm(tmp_path):
    agent, llm, store, summaries, usage, conv = await build(tmp_path, pairs=0)
    with pytest.raises(AgentError, match='максимума'):
        await agent.run(AgentCommand(conversation_id=conv.id, message='hi', max_output_tokens=10**30))
    assert not llm.calls
    assert not (await store.get('dialogue', conv.id)).messages


def test_api_setting_survives_restart_and_can_be_reset(tmp_path):
    settings = Settings(mode='demo', database_path=tmp_path/'api.sqlite3', _env_file=None)
    root = '/api/v1/agents/dialogue'
    with TestClient(create_app(settings)) as client:
        conv = client.post(root+'/conversations', json={}).json()
        data = {'conversation_id': conv['id'], 'message': 'hi', 'max_output_tokens': 1200}
        assert client.post(root+'/runs', json=data).status_code == 200
    with TestClient(create_app(settings)) as client:
        assert client.get(root+'/conversations/'+conv['id']).json()['max_output_tokens'] == 1200
        assert client.post(root+'/preview', json={'conversation_id': conv['id']}).json()['reserved_output_tokens'] == 1200
        data['max_output_tokens'] = None
        response = client.post(root+'/runs', json=data)
        assert response.status_code == 200
        assert response.json()['run']['estimate']['reserved_output_tokens'] is None
        assert client.get(root+'/conversations/'+conv['id']).json()['max_output_tokens'] is None
