"""Одинаковая исходная история → две копии → сравнение фактов и ВСЕХ затрат.

Без --execute не создаёт чаты и не вызывает LLM. С флагом: 5 вызовов подготовки,
по 3 ответа в каждой копии и обычно 2 вызова сжатия (около 13 вызовов всего).
Работает через HTTP приложения. Никаких ключей, скрытой записи в БД или удаления чатов.
"""
import argparse
import json
from decimal import Decimal
from pathlib import Path
import httpx

EXPECTED = {'project': 'Маяк', 'code': 'ORBIT-73', 'budget_rub': 52000, 'database': 'SQLite',
            'language': 'Python', 'api_owner': 'Анна', 'ui_owner': 'Борис'}
QUESTION = ('Верни только JSON без markdown с полями project, code, budget_rub (число), database, '
            'language, api_owner, ui_owner. Используй последние согласованные значения из нашего диалога.')


def quality(reply):
    """Узкая проверка семи контрольных фактов, не универсальная оценка качества LLM."""
    try:
        parsed = json.loads(reply.strip().removeprefix('```json').removesuffix('```').strip())
        return {key: parsed.get(key) == value for key, value in EXPECTED.items()}
    except (ValueError, AttributeError):
        return {key: False for key in EXPECTED}


def costs(runs):
    return {'known_tokens': sum(r['usage']['total_tokens'] for r in runs if r['usage']),
            'estimated_usd': str(sum((Decimal(r['estimated_cost_usd']) for r in runs if r['estimated_cost_usd'] is not None), Decimal(0))),
            'unknown_calls': sum(r['usage'] is None for r in runs),
            'summary_calls': sum(r.get('purpose') == 'summary' for r in runs)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8083')
    parser.add_argument('--model', default='deepseek-v4-flash')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--output', default='docs/day9-results.json')
    parser.add_argument('--max-output-tokens', type=int, default=None, help='Лимит ответа для теста, например 1200; иначе настройки API')
    args = parser.parse_args()
    if args.max_output_tokens is not None and args.max_output_tokens < 1:
        parser.error('--max-output-tokens must be positive')
    print(f'Day 9 comparison on {args.model}; about 13 API calls only with --execute.', flush=True)
    if not args.execute:
        return
    with httpx.Client(base_url=args.base_url, timeout=650) as client:
        root = '/api/v1/agents/dialogue/conversations'
        catalog = client.get('/api/v1/models').raise_for_status().json()['models']
        model = next(m for m in catalog if m['id'] == args.model)
        if not model['available']:
            raise SystemExit('Provider key is not configured.')
        source = client.post(root, json={'title': 'Day 9 · исходный сценарий'}).raise_for_status().json()
        notes = ' Это второстепенные заметки: обсудили оформление, повторили общие идеи, уточнили порядок обсуждения.' * 65
        seeds = [
            'Учебный проект называется Маяк, код ORBIT-73. Бюджет 45000 рублей. Язык Python.',
            'Исправление: актуальный бюджет 52000 рублей, а не 45000. Код и название прежние.',
            'База данных — SQLite. Оригинальную историю сохраняем полностью. Внешнюю БД не используем.',
            'Интерфейс на Vue. Язык бэкенда остаётся Python. Нужен русский интерфейс.',
            'Ответственная за API — Анна, за UI — Борис. Не меняем предыдущие договорённости.',
        ]
        for index, text in enumerate(seeds):
            result = client.post('/api/v1/agents/dialogue/runs', json={'conversation_id': source['id'],
                'model_id': args.model, 'max_output_tokens': args.max_output_tokens, 'message': text + notes + '\nЗапомни важные факты. Ответь только: принято.'})
            result.raise_for_status()
            print(f'Seed {index + 1}/5 saved.', flush=True)
        baseline = client.get(root+'/'+source['id']).raise_for_status().json()
        report = {'model': args.model, 'source_conversation_id': source['id'], 'source_messages': baseline['messages'],
                  'preparation_costs': costs(baseline['runs']), 'question': QUESTION, 'expected': EXPECTED, 'branches': {}}
        for mode in ('full', 'summary'):
            fork = client.post(root+'/'+source['id']+'/fork', json={'mode': mode, 'keep_last': 4, 'summarize_every': 4}).raise_for_status().json()
            before = client.get(root+'/'+fork['id']).raise_for_status().json()
            assert before['messages'] == baseline['messages'] and not before['runs']
            results = []
            for iteration in range(3):
                response = client.post('/api/v1/agents/dialogue/runs', json={'conversation_id': fork['id'],
                    'model_id': args.model, 'max_output_tokens': args.max_output_tokens, 'message': QUESTION})
                if response.is_error:
                    results.append({'iteration': iteration + 1, 'error': response.json()})
                    break
                result = response.json()
                checks = quality(result['reply'])
                results.append({'iteration': iteration + 1, 'reply': result['reply'], 'checks': checks, 'run': result['run']})
                print(json.dumps({'mode': mode, 'iteration': iteration+1, 'correct_facts': sum(checks.values()),
                                  'usage': result['run']['usage']}, ensure_ascii=True), flush=True)
            after = client.get(root+'/'+fork['id']).raise_for_status().json()
            report['branches'][mode] = {'conversation_id': fork['id'], 'results': results, 'summary': after['summary'],
                                        'runs': after['runs'], 'costs': costs(after['runs'])}
        Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({mode: branch['costs'] for mode, branch in report['branches'].items()}, ensure_ascii=True), flush=True)
        print('Report: '+args.output, flush=True)


if __name__ == '__main__':
    main()
