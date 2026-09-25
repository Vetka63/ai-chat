"""Проверки расписания, курсора и MCP-сохранения сводки без внешнего API."""

from datetime import UTC, datetime, timedelta

import pytest
from mcp import Client

from game_feed.repository import FeedRepository
from game_feed.server import create_server


@pytest.mark.asyncio
async def test_mcp_schedule_save_restart_and_keep_three(tmp_path):
    db_path = tmp_path / "feed.sqlite3"
    repository = FeedRepository(db_path)
    server = create_server(repository)
    async with Client(server) as client:
        names = {tool.name for tool in (await client.list_tools()).tools}
        assert names == {"create_watch", "get_watch", "cancel_watch", "list_reports",
                         "delete_watch", "claim_due", "release_failed", "save_digest"}
        created = await client.call_tool("create_watch", {"conversation_id": "chat-1", "interval_seconds": 1800})
        assert created.structured_content["watch"]["status"] == "active"
        assert datetime.fromisoformat(created.structured_content["watch"]["next_run_at"]) > datetime.now(UTC)
        for i in range(5):
            due = f"2026-09-24T12:{i:02d}:00+00:00"
            games = [{"id": i + 1, "title": f"Игра {i + 1}", "genre": "Приключение",
                      "description": "Описание", "created_at": due}]
            result = await client.call_tool("save_digest", {
                "watch_id": created.structured_content["watch"]["id"],
                "scheduled_for": due, "latest_id": i + 1,
                "games": games, "text": f"Сводка {i + 1}",
            })
            assert not result.is_error
        repeated = await client.call_tool("save_digest", {
            "watch_id": created.structured_content["watch"]["id"],
            "scheduled_for": due, "latest_id": i + 1,
            "games": games, "text": f"Сводка {i + 1}",
        })
        assert not repeated.is_error
        reports = (await client.call_tool("list_reports", {"conversation_id": "chat-1"})).structured_content["reports"]
        assert len(reports) == 3
        assert reports[0]["stats"]["runs"] == 5
        assert reports[0]["stats"]["games"][0]["title"] == "Игра 5"
    restarted = FeedRepository(db_path)
    assert len(restarted.list_reports("chat-1")) == 3
    assert restarted.get_watch("chat-1")["status"] == "active"
    assert restarted.get_watch("chat-1")["last_game_id"] == 5
    assert restarted.cancel_watch("chat-1")["status"] == "stopped"


def test_due_claim_and_failure_retry(tmp_path):
    repository = FeedRepository(tmp_path / "feed.sqlite3")
    repository.create_watch("chat-2", 1800)
    assert repository.claim_due() == []
    with repository.connection() as db:
        db.execute("UPDATE watches SET next_run_at=? WHERE conversation_id=?",
                   ((datetime.now(UTC) - timedelta(seconds=1)).isoformat(), "chat-2"))
    claimed = repository.claim_due()
    assert len(claimed) == 1
    assert repository.claim_due() == []
    repository.release_failed(claimed[0]["id"], claimed[0]["scheduled_for"])
    assert len(repository.claim_due()) == 1
