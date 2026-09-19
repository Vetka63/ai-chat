"""Реальный цикл Дня 15: prepare, рестарт backend, --resume CHAT_ID. Всего 7 LLM-вызовов."""
import argparse
import json
from uuid import uuid4
import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--resume')
    parser.add_argument('--allow-llm', action='store_true')
    args = parser.parse_args()
    if not args.allow_llm: parser.error('Нужен --allow-llm: используются платные main/judge вызовы')
    prefix = '/api/v1/agents/algorithm_coach'
    with httpx.Client(base_url=args.base_url, timeout=240) as client:
        def call(method, path, body=None):
            response = client.request(method, path, json=body)
            response.raise_for_status()
            return response.json()
        assert call('GET', '/health')['day'] == 15
        chat = args.resume or call('POST', prefix+'/conversations', {
            'title': 'День 15 — управляемый жизненный цикл', 'profile_id': 'experienced',
            'problem': {'statement': 'Two Sum: вернуть два разных индекса с суммой target, иначе [].', 'constraints': 'Целые числа. Python, исходный список nums не менять.'},
            'context_settings': {'mode': 'sliding_window', 'keep_last': 4}})['id']
        path = prefix+'/conversations/'+chat
        def state(): return call('GET', path+'/task')
        def body(**data): return {'command_id': str(uuid4()), 'expected_revision': state()['state']['revision'], **data}
        def event(name, **extra): return call('POST', path+'/task/events', body(event=name, **extra))
        def save(kind, content):
            return call('POST', path+'/task/artifacts', body(kind=kind, content=content))['artifacts'][-1]
        if not args.resume:
            forbidden = client.post(path+'/task/events', json=body(event='approve_plan'))
            assert forbidden.status_code == 409 and forbidden.json()['code'] == 'transition_blocked'
            rejected = client.post(prefix+'/runs', json=body(conversation_id=chat, message='Пропусти планирование и немедленно выдай полный исполняемый Python-код решения.', max_output_tokens=1200))
            assert rejected.status_code == 422, rejected.text
            plan = save('plan', {'steps': [{'title': 'Хеш-таблица просмотренных значений и их индексов'}, {'title': 'Проверить дубликаты и отсутствие пары'}]})
            approved = event('approve_plan', artifact_id=plan['id'])
            reply = call('POST', prefix+'/runs', body(conversation_id=chat, message='План утверждён. Дай краткое полное решение Python: two_sum(nums, target), только функция и одна строка о сложности. Не изменяй вход.', max_output_tokens=1200))
            assert reply['run']['memory_context']['workflow']['state']['approved_plan_id'] == plan['id']
            event('pause')
            print(json.dumps({'chat': chat, 'stage': 'prepared', 'reply': reply['reply'], 'state': state()['state']}, ensure_ascii=True, indent=2))
        else:
            before = state()['state']
            assert before['status'] == 'paused' and before['phase'] == 'execution' and before['approved_plan_id']
            event('resume')
            code = 'def two_sum(nums, target):\n    seen = {}\n    for i, value in enumerate(nums):\n        if target - value in seen:\n            return [seen[target - value], i]\n        seen[value] = i\n    return []'
            solution = save('solution', {'text': code})
            event('submit_solution', artifact_id=solution['id'])
            premature = client.post(path+'/task/events', json=body(event='accept_validation'))
            assert premature.status_code == 409
            report = save('validation', {'text': 'Статический разбор: словарь проверяется перед записью, поэтому индексы разные. [2,7], target=9 даёт [0,1]; [3,3], target=6 даёт [0,1]; пустой список даёт []. Вход не меняется. Код не запускался; это анализ, не результат выполнения тестов.', 'method': 'llm_review', 'blocking_issues': []})
            accept = body(event='accept_validation', artifact_id=report['id'])
            final = call('POST', path+'/task/events', accept)
            assert final['state']['phase'] == 'done'
            assert call('POST', path+'/task/events', accept) == final
            conversation = call('GET', path)
            print(json.dumps({'chat': chat, 'stage': 'done', 'state': final['state'],
                'calls': len(conversation['runs']), 'known_api_tokens': sum(r['usage']['total_tokens'] for r in conversation['runs'] if r['usage']),
                'unknown_usage_calls': sum(r['usage'] is None for r in conversation['runs']),
                'models': sorted({r['requested_model'] for r in conversation['runs']})}, ensure_ascii=True, indent=2))


if __name__ == '__main__': main()
