"""Фоновый планировщик; намеренно вызывает сбор по MCP, а не напрямую."""

import asyncio
import logging
import os

from mcp import Client

from game_feed.repository import FeedRepository

logger = logging.getLogger(__name__)


async def run_once(repository: FeedRepository, mcp_url: str) -> int:
    """Резервирует наступившие задания и запускает MCP-инструмент."""
    claimed = repository.claim_due()
    for watch in claimed:
        try:
            async with Client(mcp_url) as client:
                result = await client.call_tool("collect_games", {
                    "watch_id": watch["id"], "scheduled_for": watch["scheduled_for"],
                })
                if result.is_error:
                    raise RuntimeError("MCP-инструмент вернул ошибку")
            logger.info("game_feed_collected watch_id=%s scheduled_for=%s", watch["id"], watch["scheduled_for"])
        except Exception:
            logger.exception("game_feed_collect_failed watch_id=%s", watch["id"])
            repository.release_failed(watch["id"], watch["scheduled_for"])
    return len(claimed)


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    repository = FeedRepository(os.environ.get("GAME_FEED_DB", "data/game-feed.sqlite3"))
    mcp_url = os.environ.get("GAME_FEED_MCP_URL", "http://localhost:8080/mcp")
    while True:
        await run_once(repository, mcp_url)
        await asyncio.sleep(float(os.environ.get("GAME_FEED_POLL_SECONDS", "5")))


if __name__ == "__main__":
    asyncio.run(main())
