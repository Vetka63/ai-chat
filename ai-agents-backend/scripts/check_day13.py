"""Демонстрация паузы/рестарта: prepare, перезапуск backend, resume; по одному LLM-вызову."""
import argparse
import json
from uuid import uuid4
import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--resume', metavar='CHAT_ID')
    parser.add_argument('--allow-llm', action='store_true')
    args = parser.parse_args()
    if not args.allow_llm:
        parser.error('Нужен --allow-llm: выполняется один потенциально платный запрос')
    prefix = '/api/v1/agents/algorithm_coach'
    with httpx.Client(base_url=args.base_url, timeout=150) as client:
        def call(method, path, body=None):
            response = client.request(method, path, json=body)
            response.raise_for_status()
            return response.json()
        assert call('GET', '/health')['day'] == 13
        chat = args.resume or call('POST', prefix+'/conversations', {
            'title': 'День 13 — продолжение после паузы', 'profile_id': 'experienced',
            'problem': {'statement': 'Two Sum: вернуть два различных индекса с суммой target, иначе [].',
                        'constraints': 'Python. Не менять входной массив. Любая подходящая пара.'},
            'context_settings': {'mode': 'sliding_window', 'keep_last': 2}})['id']
        path = prefix+'/conversations/'+chat
        def state(): return call('GET', path+'/task')
        def command(resource, **body):
            return call('POST', path+'/task/'+resource, {'command_id': str(uuid4()),
                'expected_revision': state()['state']['revision'], **body})
        if not args.resume:
            saved = command('artifacts', kind='plan', content={'steps': [
                {'title': 'Выбрать хеш-таблицу дополнений'},
                {'title': 'Проверить случай [3,3], target=6 и разные индексы'},
                {'title': 'Обсудить пустой массив и отсутствие пары'}]})
            step_id = saved['artifacts'][-1]['content']['steps'][1]['id']
            command('events', event='start_execution')
            command('step', step_id=step_id)
            prompt = 'Кратко объясни текущий шаг сохранённого плана, без полного кода (до 150 слов).'
        else:
            before = state()
            assert before['state']['status'] == 'paused' and before['state']['phase'] == 'execution'
            step_id = before['state']['current_step_id']
            command('events', event='resume')
            prompt = 'Продолжим с того места, где остановились. Напомни наш текущий шаг и объясни его на примере. До 150 слов, без полного кода.'
        body = {'conversation_id': chat, 'command_id': str(uuid4()), 'expected_revision': state()['state']['revision'],
                'message': prompt, 'max_output_tokens': 1200}
        result = call('POST', prefix+'/runs', body)
        repeated = call('POST', prefix+'/runs', body)
        assert result == repeated, 'Повтор команды должен вернуть сохранённый ответ'
        snapshot = result['run']['memory_context']['workflow']
        assert snapshot['state']['current_step_id'] == step_id
        assert snapshot['artifacts']['plan']['content']['steps'][1]['title'].startswith('Проверить случай')
        if not args.resume:
            command('events', event='pause')
            rejected = client.post(prefix+'/runs', json={'conversation_id': chat, 'message': 'Не должно уйти в LLM'})
            assert rejected.status_code == 409 and rejected.json()['code'] == 'task_paused'
        print(json.dumps({'chat': chat, 'state': state()['state'], 'reply': result['reply'],
            'usage': result['run']['usage'], 'duration_ms': result['run']['duration_ms'],
            'run_id': result['run']['id'], 'result': 'ok'}, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
