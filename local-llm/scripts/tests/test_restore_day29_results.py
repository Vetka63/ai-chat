"""Проверки офлайн-импорта; реальные модели и сохранённые artifacts не изменяются."""

import importlib.util
import json
import sqlite3
import tempfile
import unittest
import uuid
from contextlib import closing
from pathlib import Path

spec = importlib.util.spec_from_file_location("restore_day29", Path(__file__).parents[1] / "restore-day29-results.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RestoreTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.database = self.root / "db" / "optimization.sqlite"
        self.identifier = str(uuid.uuid4())
        self.record = dict(id=self.identifier, createdAt="2026-10-08T10:00:00Z", request=dict(id=self.identifier),
                           model="qwen3:4b-q4_K_M", promptVersion="incident-card-v3", status="PARTIAL",
                           items=[dict(status="FAILED", messages=[], error="original error")], note="Сохранённый вывод")

    def save(self, folder="day29-test", record=None):
        path = self.root / folder / "result.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(record or self.record, ensure_ascii=False), encoding="utf-8-sig")

    def test_restores_original_errors_and_notes(self):
        self.save()
        result = module.restore(self.root, self.database)
        self.assertEqual((result["imported"], result["total"], result["integrity"]), (1, 1, "ok"))
        with closing(sqlite3.connect(self.database)) as db:
            saved = json.loads(db.execute("SELECT payload FROM optimization_experiments").fetchone()[0])
        self.assertEqual(saved, self.record)

    def test_repeated_import_is_idempotent(self):
        self.save()
        module.restore(self.root, self.database)
        result = module.restore(self.root, self.database)
        self.assertEqual((result["imported"], result["unchanged"], result["total"]), (0, 1, 1))

    def test_conflicting_existing_result_rolls_back_entire_import(self):
        self.save()
        module.restore(self.root, self.database)
        new_id = str(uuid.uuid4())
        new_record = {**self.record, "id": new_id, "request": {"id": new_id}}
        self.save("day29-a-new", new_record)
        self.save(record={**self.record, "note": "different"})
        with self.assertRaises(ValueError):
            module.restore(self.root, self.database)
        with closing(sqlite3.connect(self.database)) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM optimization_experiments").fetchone()[0], 1)

    def test_running_snapshot_rejected_before_database_creation(self):
        self.save(record={**self.record, "status": "RUNNING"})
        with self.assertRaises(ValueError):
            module.restore(self.root, self.database)
        self.assertFalse(self.database.exists())

    def test_request_id_mismatch_is_rejected(self):
        self.save(record={**self.record, "request": {"id": str(uuid.uuid4())}})
        with self.assertRaises(ValueError):
            module.restore(self.root, self.database)
        self.assertFalse(self.database.exists())

    def test_other_days_are_not_imported(self):
        self.save(folder="day27-test")
        with self.assertRaises(ValueError):
            module.restore(self.root, self.database)
        self.assertFalse(self.database.exists())

    def test_conflicting_files_are_rejected_before_database_creation(self):
        self.save("day29-a")
        self.save("day29-b", {**self.record, "note": "different"})
        with self.assertRaises(ValueError):
            module.restore(self.root, self.database)
        self.assertFalse(self.database.exists())


if __name__ == "__main__":
    unittest.main()
