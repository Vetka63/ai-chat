"""Отдельный процесс расписания: будит GameDigestAgent, не вызывает MCP напрямую."""

import asyncio
import logging
import os

from agents.game_digest.agent import GameCatalogGateway, GameDigestAgent, GameFeedGateway
from application.settings import Settings
from infrastructure.sqlite_store import SqliteConversationStore

logger = logging.getLogger(__name__)


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    settings = Settings()
    store = SqliteConversationStore(settings.database_path)
    await store.initialize()
    agent = GameDigestAgent(store, GameFeedGateway(settings.game_feed_mcp_url),
                            GameCatalogGateway(settings.java_mcp_url))
    poll_seconds = float(os.environ.get("GAME_DIGEST_POLL_SECONDS", "5"))
    while True:
        try:
            await agent.run_due()
        except Exception:
            logger.exception("game_digest_scheduler_failed")
        await asyncio.sleep(poll_seconds)


if __name__ == "__main__":
    asyncio.run(main())
