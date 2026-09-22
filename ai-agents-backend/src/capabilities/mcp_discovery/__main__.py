"""CLI-проверка дня 16: соединиться с MCP и вывести tools/list в JSON."""

import asyncio
import json

from capabilities.mcp_discovery.service import McpDiscoveryService


async def main() -> None:
    result = await McpDiscoveryService().discover("local-demo")
    print(json.dumps(result.model_dump(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
