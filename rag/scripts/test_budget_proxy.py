"""Бесплатные регрессии денежного шлюза, сеть не используется."""
import concurrent.futures
import json
import tempfile
import unittest
from pathlib import Path
from budget_proxy import Ledger, request_reserve, rate_factor


class BudgetTests(unittest.TestCase):
    def test_explicit_pro_cap_is_mandatory(self):
        request = {'model': 'deepseek-v4-pro', 'stream': False, 'messages': [{'role': 'user', 'content': 'Привет'}], 'max_tokens': 1200}
        self.assertGreater(request_reserve(request), 1200 * 27)
        for patch in ({'max_tokens': None}, {'max_tokens': 20000}, {'model': 'deepseek-flash'}, {'tools': []}, {'stream': True}):
            with self.assertRaises(ValueError):
                request_reserve(request | patch)

    def test_json_instruction_before_reserving_money(self):
        request = {'model': 'deepseek-v4-pro', 'stream': False, 'messages': [{'role': 'user', 'content': 'Привет'}], 'max_tokens': 1200, 'response_format': {'type': 'json_object'}}
        with self.assertRaises(ValueError):
            request_reserve(request)
        request['messages'][0]['content'] = 'Верни JSON'
        self.assertGreater(request_reserve(request), 0)

    def test_parallel_reservations_cannot_overspend(self):
        with tempfile.TemporaryDirectory() as folder:
            ledger = Ledger(Path(folder) / 'ledger.json', 5)
            def submit(_):
                try:
                    return ledger.reserve(1_000_000)
                except ValueError:
                    return None
            with concurrent.futures.ThreadPoolExecutor(8) as pool:
                results = list(pool.map(submit, range(20)))
            self.assertEqual(5, sum(bool(r) for r in results))
            self.assertEqual(5, ledger.snapshot()['committedUpperCny'])
            ledger.file_lock.close()

    def test_unknown_and_restart_retain_reservation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ledger.json'
            ledger = Ledger(path)
            unknown = ledger.reserve(500_000)
            ledger.finish(unknown, {'usage': {'prompt_tokens': -1}})
            ledger.reserve(500_000)
            ledger.file_lock.close()
            resumed = Ledger(path)
            self.assertEqual(1, resumed.snapshot()['committedUpperCny'])
            self.assertEqual(2, resumed.snapshot()['unknown'])
            resumed.file_lock.close()

    def test_settlement_is_durable_and_does_not_store_content(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ledger.json'
            ledger = Ledger(path)
            call_id = ledger.reserve(500_000)
            ledger.finish(call_id, {'usage': {'prompt_tokens': 1000, 'completion_tokens': 1000, 'total_tokens': 2000}, 'choices': ['private text']})
            self.assertLessEqual(ledger.snapshot()['committedUpperCny'], .036)
            self.assertNotIn('private text', path.read_text())
            self.assertEqual('settled', json.loads(path.read_text())['calls'][0]['status'])
            ledger.file_lock.close()

    def test_off_peak_and_peak(self):
        import datetime as dt
        def stamp(s):
            return dt.datetime.fromisoformat(s).timestamp()
        self.assertEqual(.5, rate_factor(stamp('2026-10-04T20:00:00+00:00'), stamp('2026-10-04T20:04:00+00:00')))
        self.assertEqual(1, rate_factor(stamp('2026-10-05T00:59:00+00:00'), stamp('2026-10-05T01:01:00+00:00')))

    def test_full_ceiling_has_safety_margin_and_cannot_be_raised(self):
        with tempfile.TemporaryDirectory() as folder:
            ledger = Ledger(Path(folder) / 'ledger.json', 40)
            ledger.reserve(39_000_000)
            with self.assertRaises(ValueError):
                ledger.reserve(1)
            ledger.file_lock.close()
            with self.assertRaises(ValueError):
                Ledger(Path(folder) / 'other.json', 41)

    def test_upper_bound_violation_stays_blocked_after_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ledger.json'
            ledger = Ledger(path)
            call_id = ledger.reserve(1)
            ledger.finish(call_id, {'usage': {'prompt_tokens': 100, 'completion_tokens': 100, 'total_tokens': 200}})
            ledger.file_lock.close()
            resumed = Ledger(path)
            with self.assertRaises(ValueError):
                resumed.reserve(1)
            resumed.file_lock.close()


if __name__ == '__main__':
    unittest.main()
