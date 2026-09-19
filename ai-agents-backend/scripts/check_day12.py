"""Сравнение профилей через реальный API: три вызова с одинаковой базовой задачей."""
import argparse
import json
import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--allow-llm', action='store_true')
    args = parser.parse_args()
    if not args.allow_llm:
        parser.error('Укажите --allow-llm: будут выполнены три потенциально платных вызова')
    prefix = '/api/v1/agents/algorithm_coach'
    report = {'chats': [], 'runs': []}
    prompt = 'Объясни, как найти индексы двух разных элементов массива с суммой target за O(n). Для [2,7,11,15] и target=9 дай алгоритм, пример и оценку сложности.'
    problem = {'statement': 'Найти индексы двух разных элементов с суммой target, либо пустой список.',
               'constraints': 'Не менять исходный массив. Допустима любая подходящая пара.'}
    with httpx.Client(base_url=args.base_url, timeout=150) as client:
        def call(method, path, body=None):
            response = client.request(method, path, json=body)
            if not response.is_success:
                raise RuntimeError(f'{method} {path}: HTTP {response.status_code}: {response.text[:500]}')
            return response.json()

        assert call('GET', '/health')['day'] >= 12
        before = {}
        for persona in ('beginner', 'experienced'):
            before[persona] = call('GET', '/api/v1/profiles/'+persona)
            chat = call('POST', prefix+'/conversations', {
                'title': 'День 12 — '+before[persona]['name'], 'profile_id': persona, 'problem': problem,
                'context_settings': {'mode': 'sliding_window', 'keep_last': 4}})
            report['chats'].append(chat['id'])
            workspace = call('GET', prefix+'/conversations/'+chat['id']+'/memory')
            assert not [e for e in workspace['long_term'] if e['active']], 'Для чистого сравнения профили должны быть без активных заметок'
            result = call('POST', prefix+'/runs', {'conversation_id': chat['id'], 'message': prompt, 'max_output_tokens': 1200})
            run = result['run']
            assert run['memory_context']['profile']['id'] == persona
            report['runs'].append({'profile': persona, 'profile_revision': run['memory_context']['profile']['revision'],
                'usage': run['usage'], 'duration_ms': run['duration_ms'], 'requested_model': run['requested_model'],
                'returned_model': run['returned_model'], 'reply': result['reply']})
        assert report['runs'][0]['requested_model'] == report['runs'][1]['requested_model']
        result = call('POST', prefix+'/runs', {'conversation_id': report['chats'][0], 'max_output_tokens': 1200,
            'message': 'Only for this answer: in English, explain the key idea in one short sentence, without code.'})
        report['runs'].append({'profile': 'beginner / explicit override', 'reply': result['reply'], 'usage': result['run']['usage']})
        assert call('GET', '/api/v1/profiles/beginner') == before['beginner']
        report['result'] = 'ok'
        print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
