"""Офлайн-восстановление сохранённых JSON дня 29 в SQLite без вызовов LLM.

Это административная утилита на стандартной библиотеке Python, не часть Kotlin
backend. Запускайте на отдельной копии БД при остановленном backend. Старые
ответы, версии prompt, оценки и ошибки сохраняются без пересчёта.
"""

import argparse
import json
import sqlite3
import uuid
from datetime import datetime
from pathlib import Path


def read_snapshots(artifacts: Path) -> list[tuple[str, str, str]]:
    """Читает только законченные result.json, проверяя все файлы до записи БД."""
    snapshots = {}
    for path in sorted(artifacts.glob("day29-*/result.json")):
        raw = path.read_text(encoding="utf-8-sig")
        record = json.loads(raw)
        required = {"id", "createdAt", "request", "model", "promptVersion", "status", "items"}
        if not isinstance(record, dict) or not required.issubset(record):
            raise ValueError(f"Неполный снимок: {path}")
        identifier = record["id"]
        if str(uuid.UUID(identifier)) != identifier or record["request"].get("id") != identifier:
            raise ValueError(f"Некорректный UUID: {path}")
        datetime.fromisoformat(record["createdAt"].replace("Z", "+00:00"))
        if record["status"] not in {"COMPLETED", "PARTIAL", "FAILED", "INTERRUPTED"}:
            raise ValueError(f"Незавершённый эксперимент не импортируется: {path}")
        if not isinstance(record["items"], list) or not record["items"]:
            raise ValueError(f"Нет сохранённых результатов: {path}")
        for item in record["items"]:
            if item.get("status") not in {"COMPLETED", "TRUNCATED", "FAILED", "INTERRUPTED"}:
                raise ValueError(f"Незавершённый ход не импортируется: {path}")
            if not isinstance(item.get("messages"), list):
                raise ValueError(f"Нет исходных сообщений: {path}")
        if identifier in snapshots and json.loads(snapshots[identifier][2]) != record:
            raise ValueError(f"Конфликт снимков одного UUID: {identifier}")
        snapshots[identifier] = (identifier, record["createdAt"], raw)
    if not snapshots:
        raise ValueError("Сохранённые result.json дня 29 не найдены")
    return list(snapshots.values())


def restore(artifacts: Path, database: Path) -> dict:
    """Атомарно добавляет снимки; совпадающие пропускает, отличающиеся не затирает."""
    snapshots = read_snapshots(artifacts)
    database.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(database)
    imported = skipped = 0
    try:
        db.execute("PRAGMA busy_timeout=10000")
        with db:
            db.execute("CREATE TABLE IF NOT EXISTS optimization_experiments "
                       "(id TEXT PRIMARY KEY, created_at TEXT NOT NULL, payload TEXT NOT NULL)")
            for identifier, created_at, raw in snapshots:
                existing = db.execute("SELECT payload FROM optimization_experiments WHERE id=?", (identifier,)).fetchone()
                if existing:
                    if json.loads(existing[0]) != json.loads(raw):
                        raise ValueError(f"БД уже содержит другой результат UUID {identifier}; импорт отменён")
                    skipped += 1
                    continue
                db.execute("INSERT INTO optimization_experiments(id,created_at,payload) VALUES(?,?,?)",
                           (identifier, created_at, raw))
                imported += 1
        integrity = [row[0] for row in db.execute("PRAGMA integrity_check")]
        if integrity != ["ok"]:
            raise ValueError(f"SQLite integrity_check: {integrity}")
        return {"imported": imported, "unchanged": skipped,
                "total": db.execute("SELECT COUNT(*) FROM optimization_experiments").fetchone()[0],
                "integrity": "ok", "database": str(database.resolve())}
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--database", type=Path, required=True)
    options = parser.parse_args()
    print(json.dumps(restore(options.artifacts, options.database), ensure_ascii=False))
