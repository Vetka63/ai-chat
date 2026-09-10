"""Воспроизводимый эксперимент через HTTP приложения, без прямого доступа к ключам.

По умолчанию только печатает план. --execute создаёт отдельный тестовый чат
и выполняет до трёх платных запросов: короткий, длинный, переполнение.
Большие синтетические сообщения остаются в этом тестовом чате; реальные чаты не меняются.
"""
import argparse
import json
from pathlib import Path
import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8083')
    parser.add_argument('--model', default='ministral-3b-2512')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--output', help='Файл JSON с метриками (без текстов диалога)')
    parser.add_argument('--through', choices=['short', 'long', 'overflow'], default='overflow')
    args = parser.parse_args()
    print(f'Model: {args.model}; scenarios through {args.through}. Paid calls only with --execute.', flush=True)
    if not args.execute:
        return
    with httpx.Client(base_url=args.base_url, timeout=330) as client:
        models = client.get('/api/v1/models').raise_for_status().json()['models']
        spec = next(m for m in models if m['id'] == args.model)
        if not spec['available']:
            raise SystemExit('Provider key is not configured. No chat created.')
        conv = client.post('/api/v1/agents/dialogue/conversations', json={'title': f'Day 8 experiment · {args.model}'}).raise_for_status().json()
        path = f'/api/v1/agents/dialogue/conversations/{conv["id"]}'
        scenarios = ['short', 'long', 'overflow'][:['short', 'long', 'overflow'].index(args.through)+1]
        results = []
        for scenario in scenarios:
            text = 'Remember the code CLOVER-42. Reply with that code only.'
            if scenario != 'short':
                # Число токенов зависит от tokenizer: калибруем через бесплатный preview.
                unit = 'The garden has a green tree, a small pond and a stone path.\n'
                target = 8000 if scenario == 'long' else int(spec['context_window'] * 1.05)
                repetitions = max(1, target // 15)
                for _ in range(5):
                    text = 'Reference notes follow.\n' + unit * repetitions + '\nWhat code did I ask you to remember? Reply with the code only.'
                    estimate = client.post('/api/v1/agents/dialogue/preview', json={
                        'conversation_id': conv['id'], 'message': text, 'model_id': args.model,
                    }).raise_for_status().json()
                    if estimate['prompt_tokens'] >= target:
                        break
                    repetitions = int(repetitions * target / max(estimate['prompt_tokens'], 1) * 1.03)
            response = client.post('/api/v1/agents/dialogue/runs', json={
                'conversation_id': conv['id'], 'message': text, 'model_id': args.model,
            })
            data = client.get(path).raise_for_status().json()
            run = data['runs'][-1] if data['runs'] else None
            result = {'scenario': scenario, 'http_status': response.status_code, 'run': run}
            if not response.is_success:
                result['error'] = response.json()
            results.append(result)
            print(json.dumps({'scenario': scenario, 'http_status': response.status_code,
                'estimate_prompt': run['estimate']['prompt_tokens'] if run else None,
                'usage': run['usage'] if run else None, 'error_code': run['error_code'] if run else None}, ensure_ascii=True), flush=True)
            # Нет автоматического повтора платного вызова. После rate limit дальнейшие сценарии бессмысленны.
            if not response.is_success:
                break
        report = {'conversation_id': conv['id'], 'model': args.model, 'results': results}
        if args.output:
            Path(args.output).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print('Conversation: ' + conv['id'], flush=True)


if __name__ == '__main__':
    main()
