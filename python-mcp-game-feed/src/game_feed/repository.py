"""SQLite-хранилище подписок, игр, запусков и последних трёх сводок."""

import json
import sqlite3
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

from game_feed.source import GameSource


def now() -> datetime:
    return datetime.now(UTC)


class FeedRepository:
    def __init__(self, path: str | Path):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        self.initialize()

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA busy_timeout=10000")
        try:
            yield db
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    def initialize(self):
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS watches (
                    id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL UNIQUE,
                    interval_seconds INTEGER NOT NULL, next_run_at TEXT NOT NULL,
                    status TEXT NOT NULL, created_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS games (
                    source TEXT NOT NULL, external_id TEXT NOT NULL, title TEXT NOT NULL,
                    genre TEXT NOT NULL, description TEXT NOT NULL, added_at TEXT NOT NULL,
                    PRIMARY KEY (source, external_id)
                );
                CREATE TABLE IF NOT EXISTS runs (
                    id TEXT PRIMARY KEY, watch_id TEXT NOT NULL, scheduled_for TEXT NOT NULL,
                    created_at TEXT NOT NULL, added_game_id TEXT, error TEXT,
                    UNIQUE (watch_id, scheduled_for)
                );
                CREATE TABLE IF NOT EXISTS reports (
                    id TEXT PRIMARY KEY, watch_id TEXT NOT NULL, created_at TEXT NOT NULL,
                    text TEXT NOT NULL, stats_json TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS reports_watch ON reports(watch_id, created_at DESC);
            """)

    def get_watch(self, conversation_id: str):
        with self.connection() as db:
            row = db.execute("SELECT * FROM watches WHERE conversation_id=?", (conversation_id,)).fetchone()
            return dict(row) if row else None

    def create_watch(self, conversation_id: str, interval_seconds: int):
        if not 60 <= interval_seconds <= 86400:
            raise ValueError("Интервал должен быть от 60 секунд до 24 часов")
        timestamp = now().isoformat()
        with self.connection() as db:
            previous = db.execute("SELECT id FROM watches WHERE conversation_id=?", (conversation_id,)).fetchone()
            if previous:
                db.execute("UPDATE watches SET interval_seconds=?, status='active', next_run_at=? WHERE id=?",
                           (interval_seconds, timestamp, previous["id"]))
            else:
                db.execute("INSERT INTO watches VALUES (?, ?, ?, ?, 'active', ?)",
                           (str(uuid4()), conversation_id, interval_seconds, timestamp, timestamp))
        return self.get_watch(conversation_id)

    def cancel_watch(self, conversation_id: str):
        with self.connection() as db:
            db.execute("UPDATE watches SET status='stopped' WHERE conversation_id=?", (conversation_id,))
        return self.get_watch(conversation_id)

    def delete_watch(self, conversation_id: str):
        """При удалении чата убирает его расписание и отчёты; общий каталог остаётся."""
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT id FROM watches WHERE conversation_id=?", (conversation_id,)).fetchone()
            if row:
                db.execute("DELETE FROM reports WHERE watch_id=?", (row["id"],))
                db.execute("DELETE FROM runs WHERE watch_id=?", (row["id"],))
                db.execute("DELETE FROM watches WHERE id=?", (row["id"],))
        return {"deleted": bool(row)}

    def claim_due(self, limit: int = 10):
        """Атомарно резервирует наступившие запуски до вызова MCP."""
        timestamp = now()
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM watches WHERE status='active' AND next_run_at<=? ORDER BY next_run_at LIMIT ?",
                              (timestamp.isoformat(), limit)).fetchall()
            claimed = []
            for row in rows:
                due = row["next_run_at"]
                next_run = datetime.fromisoformat(due) + timedelta(seconds=row["interval_seconds"])
                while next_run <= timestamp:
                    next_run += timedelta(seconds=row["interval_seconds"])
                db.execute("UPDATE watches SET next_run_at=? WHERE id=?", (next_run.isoformat(), row["id"]))
                claimed.append({**dict(row), "scheduled_for": due})
            return claimed

    def release_failed(self, watch_id: str, scheduled_for: str):
        """Возвращает неудавшийся запуск в очередь; повтор безопасен благодаря UNIQUE."""
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            completed = db.execute("SELECT 1 FROM runs WHERE watch_id=? AND scheduled_for=?",
                                   (watch_id, scheduled_for)).fetchone()
            if not completed:
                db.execute("UPDATE watches SET next_run_at=? WHERE id=? AND status='active'",
                           (scheduled_for, watch_id))

    def collect(self, watch_id: str, scheduled_for: str, source: GameSource):
        """Идемпотентно создаёт запись запуска и агрегированную сводку."""
        with self.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            watch = db.execute("SELECT * FROM watches WHERE id=?", (watch_id,)).fetchone()
            if not watch or watch["status"] != "active":
                raise ValueError("Активная подписка не найдена")
            previous = db.execute("SELECT created_at FROM runs WHERE watch_id=? AND scheduled_for=?",
                                  (watch_id, scheduled_for)).fetchone()
            if previous:
                row = db.execute("SELECT * FROM reports WHERE watch_id=? AND created_at=?",
                                 (watch_id, previous["created_at"])).fetchone()
                return dict(row) if row else {"status": "already_processed"}
            known = {row["external_id"] for row in db.execute("SELECT external_id FROM games WHERE source=?", (source.name,))}
            game = source.fetch(known)
            created = now().isoformat()
            if game:
                db.execute("INSERT OR IGNORE INTO games VALUES (?, ?, ?, ?, ?, ?)",
                           (source.name, game.external_id, game.title, game.genre, game.description, created))
            db.execute("INSERT INTO runs VALUES (?, ?, ?, ?, ?, NULL)",
                       (str(uuid4()), watch_id, scheduled_for, created, game.external_id if game else None))
            total = db.execute("SELECT COUNT(*) AS n FROM games").fetchone()["n"]
            runs = db.execute("SELECT COUNT(*) AS n FROM runs WHERE watch_id=?", (watch_id,)).fetchone()["n"]
            stats = {"total_games": total, "runs": runs, "new_games": int(game is not None), "source": source.name}
            text = (f"Новая игра: {game.title} ({game.genre}). {game.description} " if game else "Новых игр в генераторе пока нет. ")
            text += f"Всего в каталоге: {total}. Запусков подписки: {runs}."
            report_id = str(uuid4())
            db.execute("INSERT INTO reports VALUES (?, ?, ?, ?, ?)",
                       (report_id, watch_id, created, text, json.dumps(stats, ensure_ascii=False)))
            db.execute("DELETE FROM reports WHERE watch_id=? AND id NOT IN (SELECT id FROM reports WHERE watch_id=? ORDER BY created_at DESC, rowid DESC LIMIT 3)",
                       (watch_id, watch_id))
            return {"id": report_id, "watch_id": watch_id, "created_at": created, "text": text, "stats": stats}

    def list_reports(self, conversation_id: str):
        watch = self.get_watch(conversation_id)
        if not watch:
            return []
        with self.connection() as db:
            rows = db.execute("SELECT * FROM reports WHERE watch_id=? ORDER BY created_at DESC, rowid DESC LIMIT 3",
                              (watch["id"],)).fetchall()
            return [{**dict(row), "stats": json.loads(row["stats_json"])} for row in rows]
