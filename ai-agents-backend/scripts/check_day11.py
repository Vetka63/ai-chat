"""Явный smoke-тест через запущенное приложение; оставляет два тестовых чата."""
import argparse
import json
import uuid

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8082')
    parser.add_argument('--allow-llm', action='store_true', help='Разрешить 3 потенциально платных LLM-вызова')
    args = parser.parse_args()
    if not args.allow_llm:
        parser.error('Для трёх тестовых LLM-вызовов явно укажите --allow-llm')
    prefix = '/api/v1/agents/algorithm_coach'
    report = {'chats': [], 'runs': []}
    marker = 'smoke-' + uuid.uuid4().hex[:8]
    with httpx.Client(base_url=args.base_url, timeout=150) as client:
        def call(method, path, body=None):
            response = client.request(method, path, json=body)
            if not response.is_success:
                raise RuntimeError(f'{method} {path}: HTTP {response.status_code}: {response.text[:500]}')
            return response.json() if response.content else None

        def path(chat):
            return prefix+'/conversations/'+chat

        def memory(chat):
            return call('GET', path(chat)+'/memory')

        def versions(chat):
            data = memory(chat)
            return {'task_revision': data['task']['revision'], 'profile_revision': data['profile']['memory_revision'],
                    'preferences_revision': data['profile']['revision']}

        def create(title, problem):
            data = call('POST', prefix+'/conversations', {'title': title,
                'problem': {'statement': problem}, 'context_settings': {'mode': 'sliding_window', 'keep_last': 2}})
            report['chats'].append(data['id'])
            return data['id']

        def record(result):
            run = result['run']
            report['runs'].append({key: run.get(key) for key in
                ('purpose', 'status', 'requested_model', 'returned_model', 'usage', 'duration_ms')})

        assert call('GET', '/health')['day'] >= 11
        first = create('День 11 — проверка Two Sum', 'Найти индексы двух разных чисел с суммой target.')
        call('POST', path(first)+'/memory/entries', {**versions(first), 'layer': 'working',
            'key': 'constraints', 'value': 'Не менять исходный массив.'})
        saved = call('POST', path(first)+'/memory/entries', {**versions(first), 'layer': 'long_term',
            'key': marker, 'value': 'Тестовая учебная метка: кедр.'})
        entry = next(e for e in saved['long_term'] if e['key'] == marker)
        try:
            result = call('POST', prefix+'/runs', {'conversation_id': first, 'max_output_tokens': 1200,
                'message': 'Для этой задачи моя цель — изучить метод поиска дополнения через словарь. Коротко повтори цель и ограничение из карточки.'})
            record(result)
            assert result['reply'].strip()
            assert result['run']['memory_context']['working'][0]['key'] == 'constraints'
            assert any(e['key'] == marker for e in result['run']['memory_context']['long_term'])
            source = next(m for m in memory(first)['short_term'] if m['role'] == 'user')
            proposed = call('POST', path(first)+'/memory/proposals',
                {**versions(first), 'source_message_id': source['id']})
            record(proposed)
            pending = [p for p in proposed['workspace']['proposals'] if p['status'] == 'pending']
            assert pending, 'Для явной цели ожидалось хотя бы одно предложение'
            chosen = next((p for p in pending if p['layer'] == 'working'), None)
            assert chosen, 'Ожидалось предложение рабочей памяти текущей задачи'
            call('POST', path(first)+'/memory/proposals/'+chosen['id'], {**versions(first), 'action': 'accept'})
            second = create('День 11 — проверка изоляции', 'Проверка правильности скобочной последовательности.')
            assert not memory(second)['working']
            result = call('POST', prefix+'/runs', {'conversation_id': second, 'max_output_tokens': 1200,
                'message': 'В одном предложении назови текущую задачу и тестовую учебную метку из долговременной памяти.'})
            record(result)
            assert not result['run']['memory_context']['working']
            assert any(e['key'] == marker for e in result['run']['memory_context']['long_term'])
            report['last_reply'] = result['reply']
            report['result'] = 'ok'
        finally:
            # Удаляем только собственную временную запись, не затрагивая пользовательскую память.
            call('POST', path(first)+'/memory/entries/'+entry['id']+'/delete', versions(first))
        print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
