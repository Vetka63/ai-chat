"""Проверки Дня 8: измерение, переключение моделей и ошибки провайдера."""
from decimal import Decimal
from pathlib import Path
import httpx
import pytest
from fastapi.testclient import TestClient
from agent_core.models import AgentCommand, AgentError, Completion, Message, PreviewCommand
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.main import create_app
from application.settings import Settings
from capabilities.token_accounting.models import TokenUsage
from capabilities.token_accounting.service import TokenAccounting, calculate_cost
from infrastructure.completions import ChatCompletionsClient
from infrastructure.model_catalog import ModelCatalog
from infrastructure.sqlite_store import SqliteConversationStore
from infrastructure.sqlite_usage import SqliteUsageRepository
from infrastructure.tokenizers import ByteEstimate, MistralEstimate

def catalog():
    return ModelCatalog.load(Path(__file__).parents[1]/'src/application/models.json', {'deepseek': True, 'mistral': True})

def test_cost_cache_and_unknown():
    usage = TokenUsage(prompt_tokens=1000, completion_tokens=100, total_tokens=1100, cached_tokens=600, reasoning_tokens=30)
    assert calculate_cost(usage, catalog().get('deepseek-v4-flash').pricing) == Decimal('0.0002436')
    assert calculate_cost(None, catalog().get('deepseek-v4-flash').pricing) is None

def test_price_time_and_actual_alias():
    models = catalog()
    assert models.pricing_at('deepseek-v4-pro', '2026-09-10T02:00:00Z').input_usd == Decimal('1.32')
    assert models.pricing_at('deepseek-v4-pro', '2026-09-10T12:00:00Z').input_usd == Decimal('0.66')
    assert models.pricing_at('deepseek-v4-pro', '2026-09-10T02:00:00Z', 'deepseek-flash').input_usd == Decimal('.30')

@pytest.mark.parametrize('status,body,code', [
    (400, 'maximum context length exceeded', 'context_limit_exceeded'),
    (422, 'prompt is too long', 'context_limit_exceeded'),
    (400, 'temperature must be positive', 'llm_unavailable'),
    (429, 'tokens per minute exceeded', 'rate_limit'),
    (413, 'body too large', 'request_too_large'),
    (401, 'invalid key', 'provider_auth'),
])
async def test_provider_errors(status, body, code):
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(status, text=body))) as http:
        with pytest.raises(AgentError) as exc:
            await ChatCompletionsClient(http, 'secret', 'mistral', 'https://test.invalid').complete(
                [Message(role='user', content='question')], model='test', temperature=.7, max_tokens=1200)
    assert (exc.value.code, exc.value.provider_status) == (code, status)

async def test_usage_on_empty_output_and_payload():
    captured = {}
    def handler(req):
        import json
        captured.update(json.loads(req.content))
        return httpx.Response(200, json={'model': 'actual', 'choices': [{'message': {'content': ''}, 'finish_reason': 'length'}],
            'usage': {'prompt_tokens': 10, 'completion_tokens': 2, 'total_tokens': 12,
            'prompt_tokens_details': {'cached_tokens': 3}, 'completion_tokens_details': {'reasoning_tokens': 2}}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as http:
        result = await ChatCompletionsClient(http, 'secret', 'mistral', 'https://test.invalid').complete(
            [Message(role='user', content='hello')], model='test', temperature=.7, max_tokens=1200)
    assert result.usage.cached_tokens == 3 and result.usage.reasoning_tokens == 2
    assert result.finish_reason == 'length' and result.content == ''
    assert 'thinking' not in captured

class FakeLlm:
    def __init__(self):
        self.calls, self.fail, self.empty = [], False, False
    async def complete(self, messages, **kwargs):
        self.calls.append((messages, kwargs))
        if self.fail:
            raise AgentError('context_limit_exceeded', 'Provider rejected context', 502, 400)
        return Completion(content='' if self.empty else 'answer', model=kwargs['model'], finish_reason='length' if self.empty else 'stop',
                          usage=TokenUsage(prompt_tokens=100, completion_tokens=10, total_tokens=110))

async def setup_agent(tmp_path):
    store = SqliteConversationStore(tmp_path/'test.sqlite3')
    await store.initialize()
    usage = SqliteUsageRepository(store)
    await usage.initialize()
    llm = FakeLlm()
    agent = build_dialogue_agent(DialogueAgentConfig(model='deepseek-v4-flash', system_prompt='Provider {provider}'), llm, store,
        catalog=catalog(), accounting=TokenAccounting({'deepseek': ByteEstimate(), 'mistral': ByteEstimate()}, usage))
    return agent, llm, store, usage, await store.create('dialogue', 'test')

async def test_model_switch_history_persistence_ownership_and_delete(tmp_path):
    agent, llm, store, usage, conv = await setup_agent(tmp_path)
    await agent.run(AgentCommand(conversation_id=conv.id, message='hello', model_id='deepseek-v4-flash'))
    await agent.run(AgentCommand(conversation_id=conv.id, message='remember?', model_id='ministral-3b-2512'))
    assert [m.content for m in llm.calls[1][0]] == ['Provider mistral', 'hello', 'answer', 'remember?']
    restarted = SqliteConversationStore(store.path)
    await restarted.initialize()
    assert (await restarted.get('dialogue', conv.id)).selected_model_id == 'ministral-3b-2512'
    saved = await SqliteUsageRepository(restarted).list('dialogue', conv.id)
    assert [r.user_index for r in saved] == [0, 2]
    assert [r.assistant_index for r in saved] == [1, 3]
    assert all(r.usage.total_tokens == 110 and r.estimated_cost_usd is not None for r in saved)
    with pytest.raises(AgentError):
        await usage.list('another-agent', conv.id)
    await store.delete('dialogue', conv.id)
    async with store.connection() as db:
        assert (await (await db.execute('SELECT count(*) FROM runs')).fetchone())[0] == 0

async def test_overflow_is_sent_and_failure_preserves_input(tmp_path):
    agent, llm, store, usage, conv = await setup_agent(tmp_path)
    agent.catalog.models['deepseek-v4-flash'].context_window = 20
    llm.fail = True
    text = 'long input ' * 2000
    with pytest.raises(AgentError):
        await agent.run(AgentCommand(conversation_id=conv.id, message=text))
    assert len(llm.calls) == 1 and llm.calls[0][0][-1].content == text.strip()
    run = (await usage.list('dialogue', conv.id))[0]
    assert run.estimate.exceeds_context and run.status == 'error' and run.provider_status == 400
    assert run.usage is None and run.estimated_cost_usd is None
    assert len((await store.get('dialogue', conv.id)).messages) == 1

async def test_empty_output_keeps_usage(tmp_path):
    agent, llm, store, usage, conv = await setup_agent(tmp_path)
    llm.empty = True
    with pytest.raises(AgentError):
        await agent.run(AgentCommand(conversation_id=conv.id, message='question'))
    record = (await usage.list('dialogue', conv.id))[0]
    assert record.usage.total_tokens == 110 and record.estimated_cost_usd > 0 and record.finish_reason == 'length'

async def test_preview_readonly_and_interrupted_recovery(tmp_path):
    agent, llm, store, usage, conv = await setup_agent(tmp_path)
    estimate = await agent.preview(PreviewCommand(message='hello'))
    assert estimate.current_message_tokens > 0
    assert not llm.calls and not (await store.get('dialogue', conv.id)).messages
    result = await agent.run(AgentCommand(conversation_id=conv.id, message='hello'))
    await usage.save(result.run.model_copy(update={'status': 'pending', 'usage': None, 'estimated_cost_usd': None}))
    await usage.initialize()
    assert (await usage.list('dialogue', conv.id))[0].status == 'interrupted'

def test_api_catalog_and_model_selection(tmp_path):
    with TestClient(create_app(Settings(mode='demo', database_path=tmp_path/'api.sqlite3', _env_file=None))) as client:
        assert len(client.get('/api/v1/models').json()['models']) == 3
        assert client.post('/api/v1/agents/dialogue/preview', json={'message': ''}).status_code == 200
        conv = client.post('/api/v1/agents/dialogue/conversations', json={}).json()
        path = f'/api/v1/agents/dialogue/conversations/{conv["id"]}'
        assert client.patch(path+'/model', json={'model_id': 'unknown'}).status_code == 422
        assert client.patch(path+'/model', json={'model_id': 'ministral-3b-2512'}).status_code == 200
        result = client.post('/api/v1/agents/dialogue/runs', json={'conversation_id': conv['id'], 'message': 'hello'}).json()
        assert result['run']['model_id'] == 'ministral-3b-2512' and result['run']['usage'] is None
        assert len(client.get(path).json()['runs']) == 1

def test_official_tokenizer_if_downloaded():
    path = Path(__file__).parents[1]/'tokenizers/ministral-3b/tekken.json'
    if not path.exists():
        pytest.skip('Download tokenizer with scripts/download_tokenizer.py')
    estimator = MistralEstimate(str(path))
    assert estimator.count_text('Привет') == 2
    assert estimator.count_messages([Message(role='system', content='system'), Message(role='user', content='one'), Message(role='user', content='two')]) > 2
