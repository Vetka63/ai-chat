"""MCP-инструмент поиска игр поверх отдельного HTTP mock API."""

import os
from typing import Any

import httpx
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError


def create_server(api_url: str, http: httpx.AsyncClient | None = None) -> MCPServer:
    """Регистрирует инструмент, не смешивая MCP-сервер с агентом или mock API."""

    mcp = MCPServer("Games Catalog")

    @mcp.tool()
    async def search_games(query: str) -> dict[str, Any]:
        """Найти вымышленные игры по словам в названии или описании.

        Args:
            query: Название игры или тема, например «Лунный архив» или «космос».
        """

        term = query.strip()
        if not term or len(term) > 100:
            raise ToolError("Запрос поиска должен содержать от 1 до 100 символов")
        try:
            if http is None:
                async with httpx.AsyncClient(base_url=api_url, timeout=4) as client:
                    response = await client.get("/games", params={"query": term})
            else:
                response = await http.get("/games", params={"query": term})
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload, dict) or not isinstance(payload.get("games"), list):
                raise ValueError("Некорректный ответ каталога")
            return {"query": term, "games": payload["games"]}
        except (httpx.HTTPError, ValueError) as exc:
            raise ToolError("Каталог игр временно недоступен") from exc

    return mcp


def main() -> None:
    """Поднимает stateless Streamable HTTP MCP на фиксированном пути /mcp."""

    api_url = os.environ.get("GAMES_API_BASE_URL", "http://localhost:8084")
    port = int(os.environ.get("PORT", "8080"))
    create_server(api_url).run(
        transport="streamable-http",
        host="0.0.0.0",
        port=port,
        streamable_http_path="/mcp",
        stateless_http=True,
        json_response=True,
    )


if __name__ == "__main__":
    main()
