"""Безопасный локальный MCP-сервер с двумя демонстрационными инструментами."""

from mcp.server import MCPServer

mcp = MCPServer("Day 16 Demo")


@mcp.tool()
def add_numbers(a: int, b: int) -> int:
    """Сложить два целых числа."""
    return a + b


@mcp.tool()
def echo_text(text: str) -> str:
    """Вернуть переданный текст без изменений."""
    return text


if __name__ == "__main__":
    mcp.run()
