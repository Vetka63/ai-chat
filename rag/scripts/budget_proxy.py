"""Тестовый шлюз DeepSeek Pro: общий денежный потолок, без retry и записи содержимого.

Только стандартная библиотека. Не часть приложения. Запускать одним процессом
в Linux-контейнере с отдельным ledger в rag/data; менять stage cap, не ledger.
Тарифы CNY проверены 2026-10-04: peak input miss 9 / output 27 за миллион.
Для расчёта игнорируем скидку cache; резерв всегда peak, расчёт usage — самый
дорогой тариф за интервал запроса. Праздничные скидки не учитываем.
"""
import datetime as dt
import fcntl
import json
import math
import os
from pathlib import Path
import threading
import time
import uuid
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def is_peak(timestamp):
    moment = dt.datetime.fromtimestamp(timestamp, dt.timezone.utc)
    return moment.weekday() < 5 and (1 <= moment.hour < 4 or 6 <= moment.hour < 10)


def rate_factor(start, end):
    # Любой пик в интервале => peak для всего ответа; неизвестные задержки
    # тарификации покрываются отдельным запасом в полном лимите запуска.
    return 1 if any(is_peak(t) for t in [start, end, *range(int(start), int(end) + 60, 60)]) else .5


def request_reserve(body):
    allowed = {'model', 'messages', 'stream', 'temperature', 'thinking', 'reasoning_effort', 'max_tokens', 'response_format'}
    if not isinstance(body, dict) or set(body) - allowed or body.get('model') != 'deepseek-v4-pro' or body.get('stream') is not False:
        raise ValueError('Only non-streaming Pro text requests are permitted')
    cap = body.get('max_tokens')
    if type(cap) is not int or not 1 <= cap <= 16384:
        raise ValueError('An explicit output cap of 1..16384 is required')
    messages = body.get('messages')
    if not isinstance(messages, list) or not 1 <= len(messages) <= 32:
        raise ValueError('Invalid messages')
    for message in messages:
        if not isinstance(message, dict) or set(message) != {'role', 'content'} or message['role'] not in ('system', 'user', 'assistant') or not isinstance(message['content'], str):
            raise ValueError('Only text messages are permitted')
    if body.get('response_format') == {'type': 'json_object'} and not any('json' in m['content'].lower() for m in messages):
        raise ValueError('JSON mode requires an explicit JSON instruction before any reservation')
    # Byte-fallback upper estimate, plus deliberately generous template overhead.
    prompt_bound = len(json.dumps(body, ensure_ascii=False).encode('utf-8')) + 4096
    return prompt_bound * 9 + cap * 27  # micro-CNY


class Ledger:
    """Атомарный общий счётчик; неизвестный исход сохраняет весь резерв."""
    def __init__(self, path, stage_cny=5, authorized_cny=40):
        # Дополнительные 20 CNY разрешены пользователем 2026-10-05.
        # Опциональное повышение не обнуляет ни вызовы, ни unknown-резервы.
        if authorized_cny not in (40, 60):
            raise ValueError('Only the explicitly authorized 40 or 60 CNY ceiling is supported')
        ceiling = round(authorized_cny * 1_000_000)
        self.stage = round(stage_cny * 1_000_000)
        if not 0 < self.stage <= ceiling:
            raise ValueError('Stage cap must be within the authorized ceiling')
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.file_lock = open(str(self.path) + '.lock', 'a')
        fcntl.flock(self.file_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.lock = threading.Lock()
        self.state = json.loads(self.path.read_text()) if self.path.exists() else {'limit': ceiling, 'calls': []}
        if self.state['limit'] != ceiling:
            if self.state['limit'] != 40_000_000 or ceiling != 60_000_000:
                self.file_lock.close()
                raise ValueError('Unexpected ledger ceiling; cannot reset or lower it implicitly')
            if any(c['status'] == 'pending' for c in self.state['calls']):
                self.file_lock.close()
                raise ValueError('Wait for all requests before applying the authorized increase')
            self.state.setdefault('limitChanges', []).append({
                'at': time.time(), 'from': self.state['limit'], 'to': ceiling,
                'reason': 'User authorized additional 20 CNY on 2026-10-05 for video scenarios',
            })
            self.state['limit'] = ceiling
        for call in self.state['calls']:
            if call['status'] == 'pending':
                call['status'] = 'unknown_after_restart'
        self.save()

    def save(self):
        temporary = self.path.with_suffix('.tmp')
        with open(temporary, 'w', encoding='utf-8') as output:
            json.dump(self.state, output, ensure_ascii=False, indent=2)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, self.path)

    def total(self):
        return sum(c.get('charged', c['reserved']) for c in self.state['calls'])

    def reserve(self, amount):
        with self.lock:
            # 1 CNY stays untouched as billing/clock safety margin.
            if self.state.get('blocked') or self.total() + amount > min(self.stage, self.state['limit'] - 1_000_000):
                raise ValueError('Monetary budget exhausted; request was not sent')
            call = {'id': str(uuid.uuid4()), 'started': time.time(), 'reserved': amount, 'status': 'pending'}
            self.state['calls'].append(call)
            self.save()  # Must succeed BEFORE network I/O.
            return call['id']

    def finish(self, call_id, response=None):
        with self.lock:
            call = next(c for c in self.state['calls'] if c['id'] == call_id)
            call['finished'] = time.time()
            call['status'] = 'unknown'
            usage = response.get('usage', {}) if isinstance(response, dict) else {}
            prompt, output, total = (usage.get(k) for k in ('prompt_tokens', 'completion_tokens', 'total_tokens'))
            if all(type(v) is int and v >= 0 for v in (prompt, output, total)) and prompt + output == total:
                factor = rate_factor(call['started'], call['finished'])
                charged = math.ceil((prompt * 9 + output * 27) * factor)
                call.update(charged=charged, usage={'prompt': prompt, 'output': output}, tariffFactor=factor, status='settled')
                if charged > call['reserved']:
                    # Fail closed on an invalid token upper bound; no further network calls.
                    self.stage = 0
                    self.state['blocked'] = True
                    call['status'] = 'bound_violation'
            self.save()

    def snapshot(self):
        with self.lock:
            return {'limitCny': self.state['limit'] / 1e6, 'stageCapCny': self.stage / 1e6, 'committedUpperCny': self.total() / 1e6,
                    'calls': len(self.state['calls']), 'pending': sum(c['status'] == 'pending' for c in self.state['calls']),
                    'unknown': sum(c['status'].startswith('unknown') for c in self.state['calls']),
                    'note': 'Conservative cost, not provider invoice; includes reservations and ignores cache discounts.'}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Never log headers, prompts, response bodies or the API key.

    def reply(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        try:
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        self.reply(200, self.server.ledger.snapshot()) if self.path == '/budget' else self.reply(404, {'error': 'not_found'})

    def do_POST(self):
        if self.path != '/chat/completions':
            return self.reply(404, {'error': 'not_found'})
        call_id = None
        try:
            length = int(self.headers.get('Content-Length', 0))
            if not 0 < length < 1_000_000:
                raise ValueError('Invalid request size')
            payload = self.rfile.read(length)
            body = json.loads(payload)
            reserve = request_reserve(body)
            authorization = self.headers.get('Authorization', '')
            if not authorization.startswith('Bearer ') or len(authorization) < 12:
                raise ValueError('Missing authorization')
            call_id = self.server.ledger.reserve(reserve)
        except (ValueError, TypeError, KeyError):
            return self.reply(429, {'error': 'budget_or_request_rejected_without_upstream_call'})
        try:
            request = urllib.request.Request('https://api.deepseek.com/chat/completions', data=payload,
                headers={'Content-Type': 'application/json', 'Authorization': authorization}, method='POST')
            with urllib.request.urlopen(request, timeout=240) as upstream:
                result = json.loads(upstream.read(8_000_000))
            self.server.ledger.finish(call_id, result)
            self.reply(200, result)
        except Exception:
            self.server.ledger.finish(call_id)  # Do not refund an unknown response, even HTTP errors.
            self.reply(502, {'error': 'upstream_failed_reservation_retained', 'call_id': call_id})


if __name__ == '__main__':
    server = ThreadingHTTPServer(('0.0.0.0', 8787), Handler)
    server.ledger = Ledger(os.environ['BUDGET_LEDGER'], float(os.environ.get('BUDGET_STAGE_CNY', '5')),
                           float(os.environ.get('BUDGET_AUTHORIZED_CNY', '40')))
    server.serve_forever()
