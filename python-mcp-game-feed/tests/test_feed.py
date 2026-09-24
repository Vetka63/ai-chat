"""Проверки хранения, MCP-контракта и worker без внешнего API."""

import random

import pytest
from mcp import Client

from game_feed.repository import FeedRepository
from game_feed.server import create_server
from game_feed.source import GeneratedGameSource


@pytest.mark.asyncio
async def test_mcp_schedule_collect_restart_and_keep_three(tmp_path):
    db_path = tmp_path / "feed.sqlite3"
    repository = FeedRepository(db_path)
    server = create_server(repository, GeneratedGameSource(random.Random(7)))
    async with Client(server) as client:
        names = {tool.name for tool in (await client.list_tools()).tools}
        assert names == {"create_watch", "get_watch", "cancel_watch", "list_reports", "delete_watch", "collect_games"}
        created = await client.call_tool("create_watch", {"conversation_id": "chat-1", "interval_seconds": 60})
        assert created.structured_content["watch"]["status"] == "active"
        for i in range(5):
            due = f"2026-09-24T12:{i:02d}:00+00:00"
            result = await client.call_tool("collect_games", {"watch_id": created.structured_content["watch"]["id"], "scheduled_for": due})
            assert not result.is_error
        repeated = await client.call_tool("collect_games", {"watch_id": created.structured_content["watch"]["id"], "scheduled_for": due})
        assert not repeated.is_error
        reports = (await client.call_tool("list_reports", {"conversation_id": "chat-1"})).structured_content["reports"]
        assert len(reports) == 3
        assert reports[0]["stats"]["runs"] == 5
    restarted = FeedRepository(db_path)
    assert len(restarted.list_reports("chat-1")) == 3
    assert restarted.get_watch("chat-1")["status"] == "active"
    assert restarted.cancel_watch("chat-1")["status"] == "stopped"


def test_due_claim_and_failure_retry(tmp_path):
    repository = FeedRepository(tmp_path / "feed.sqlite3")
    repository.create_watch("chat-2", 60)
    claimed = repository.claim_due()
    assert len(claimed) == 1
    assert repository.claim_due() == []
    repository.release_failed(claimed[0]["id"], claimed[0]["scheduled_for"])
    assert len(repository.claim_due()) == 1
