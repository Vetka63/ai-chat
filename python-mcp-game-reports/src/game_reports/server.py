"""Один HTTP MCP-сервер с тремя отдельно объявленными инструментами."""

import os
from pathlib import Path
from typing import Any

import httpx
from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from game_reports.models import ReportResult, SearchResult, SummaryResult
from game_reports.pipeline import FileReportStore, GameCatalog, PipelineError, summarize_games as summarize_search_result


def create_server(api_url: str, report_dir: Path, http: httpx.AsyncClient | None = None) -> MCPServer:
    catalog = GameCatalog(api_url, http)
    reports = FileReportStore(report_dir)
    mcp = MCPServer("Game Report Tools")

    @mcp.tool()
    async def search_games(query: str) -> dict[str, Any]:
        """Получить игры по теме из учебного каталога. Это не веб-поиск.

        Args:
            query: Тема поиска длиной от 1 до 100 символов.
        """
        term = query.strip()
        if not term or len(term) > 100:
            raise ToolError("Укажите тему поиска длиной от 1 до 100 символов")
        try:
            return (await catalog.search(term)).model_dump()
        except PipelineError as exc:
            raise ToolError(str(exc)) from exc

    @mcp.tool()
    async def summarize_games(search_result: SearchResult) -> dict[str, Any]:
        """Составить Markdown из точного результата search_games.

        Args:
            search_result: Структурированный результат предыдущего MCP-вызова.
        """
        return summarize_search_result(search_result).model_dump()

    @mcp.tool()
    async def save_report(summary_result: SummaryResult, operation_id: str) -> dict[str, Any]:
        """Сохранить результат summarize_games в Markdown-файл.

        Args:
            summary_result: Структурированный результат предыдущего MCP-вызова.
            operation_id: UUID операции для безопасного повтора.
        """
        try:
            report_id, file_name = await reports.save(summary_result, operation_id)
        except PipelineError as exc:
            raise ToolError(str(exc)) from exc
        return ReportResult(
            report_id=report_id, file_name=file_name, query=summary_result.query,
            game_count=summary_result.game_count, report_markdown=summary_result.markdown,
        ).model_dump()

    return mcp


def main() -> None:
    server = create_server(
        os.environ.get("GAMES_API_BASE_URL", "http://localhost:8084"),
        Path(os.environ.get("GAME_REPORT_DIR", "data/reports")),
    )
    server.run(
        transport="streamable-http", host="0.0.0.0",
        port=int(os.environ.get("PORT", "8080")),
        streamable_http_path="/mcp", stateless_http=True, json_response=True,
    )


if __name__ == "__main__":
    main()
