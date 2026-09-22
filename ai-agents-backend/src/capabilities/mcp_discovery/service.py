"""Подключение к доверенному MCP-серверу и чтение всех страниц tools/list."""

import asyncio
import logging
import sys
from dataclasses import dataclass
from pathlib import Path

from mcp import Client, StdioServerParameters

from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolSummary

logger = logging.getLogger(__name__)


class McpDiscoveryError(Exception):
    """Описывает ошибку обнаружения без раскрытия деталей запуска сервера.

    HTTP-слой использует ``code`` и ``status`` для ответа клиенту, а
    ``message`` показывает пользователю. Исходное исключение остаётся в логах.
    """

    def __init__(self, code: str, message: str, status: int = 502):
        """Сохраняет код, безопасный текст и HTTP-статус ошибки.

        Args:
            code: Машиночитаемый идентификатор ошибки для клиента.
            message: Сообщение, которое можно показать в интерфейсе.
            status: HTTP-статус; по умолчанию ошибка внешнего сервера.
        """
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


@dataclass(frozen=True)
class McpServerDefinition:
    """Хранит публичное описание и внутреннюю команду запуска MCP-сервера.

    Экземпляры создаёт бэкенд. Браузер передаёт только ``summary.id`` и не
    может подменить ``command`` или ``args`` произвольной программой.
    """

    summary: McpServerSummary
    command: str
    args: tuple[str, ...]


def default_servers() -> tuple[McpServerDefinition, ...]:
    """Формирует каталог разрешённых серверов для задания Дня 16.

    Сейчас в каталоге один локальный учебный сервер: его скрипт запускается
    тем же интерпретатором Python, что и бэкенд. Ключи API и сеть ему не нужны.

    Returns:
        Неизменяемый набор определений серверов.
    """

    server_file = Path(__file__).with_name("demo_server.py").resolve()
    return (
        McpServerDefinition(
            summary=McpServerSummary(
                id="local-demo",
                name="Локальный учебный сервер",
                description="Два простых инструмента для проверки подключения и tools/list.",
            ),
            command=sys.executable,
            args=(str(server_file),),
        ),
    )


class McpDiscoveryService:
    """Управляет каталогом MCP-серверов и обнаружением их инструментов.

    Сервис не выполняет инструменты. Для каждого запроса он открывает новое
    MCP-соединение, вызывает ``tools/list`` и закрывает соединение после ответа.
    """

    def __init__(self, servers: tuple[McpServerDefinition, ...] | None = None, timeout_seconds: float = 10):
        """Подготавливает разрешённые серверы и общий таймаут обнаружения.

        Args:
            servers: Явный каталог серверов, например для теста. Если не задан,
                используется каталог из ``default_servers``.
            timeout_seconds: Максимальное время на подключение и все страницы
                ``tools/list`` вместе, а не таймаут каждой страницы отдельно.
        """
        definitions = default_servers() if servers is None else servers
        self._servers = {server.summary.id: server for server in definitions}
        self._timeout_seconds = timeout_seconds

    def list_servers(self) -> list[McpServerSummary]:
        """Возвращает UI только публичные описания разрешённых серверов.

        Команда и аргументы запуска остаются на бэкенде.
        """
        return [definition.summary for definition in self._servers.values()]

    async def discover(self, server_id: str) -> McpDiscoveryResult:
        """Подключается к выбранному серверу и считывает список инструментов.

        Сначала проверяет ID по каталогу. Затем MCP SDK запускает локальный
        процесс через stdio, устанавливает соединение и последовательно читает
        страницы ``tools/list`` до отсутствия ``next_cursor``. Повтор курсора
        считается ошибкой, чтобы не зациклиться на некорректном сервере.

        Args:
            server_id: ID сервера из ``list_servers``.

        Returns:
            Имя сервера, версию протокола и описания всех найденных инструментов.

        Raises:
            McpDiscoveryError: Если ID неизвестен, время истекло или MCP-обмен
                завершился ошибкой. Детали сбоя пишутся в серверный лог.
        """
        definition = self._servers.get(server_id)
        if definition is None:
            raise McpDiscoveryError("mcp_server_not_found", "MCP-сервер не найден", 404)

        try:
            async with asyncio.timeout(self._timeout_seconds):
                parameters = StdioServerParameters(command=definition.command, args=list(definition.args))
                async with Client(parameters) as client:
                    tools: list[McpToolSummary] = []
                    cursor: str | None = None
                    seen_cursors: set[str] = set()
                    while True:
                        page = await client.list_tools(cursor=cursor)
                        tools.extend(
                            McpToolSummary(
                                name=tool.name,
                                title=tool.title or tool.name,
                                description=tool.description,
                                input_schema=tool.input_schema,
                            )
                            for tool in page.tools
                        )
                        cursor = page.next_cursor
                        if cursor is None:
                            break
                        if cursor in seen_cursors:
                            raise RuntimeError("MCP-сервер повторил курсор tools/list")
                        seen_cursors.add(cursor)

                    return McpDiscoveryResult(
                        server=definition.summary,
                        server_name=client.server_info.name if client.server_info else definition.summary.name,
                        protocol_version=str(client.protocol_version),
                        tools=tools,
                    )
        except TimeoutError as exc:
            logger.warning("mcp_discovery_timeout server_id=%s", server_id)
            raise McpDiscoveryError("mcp_timeout", "MCP-сервер не ответил вовремя", 504) from exc
        except Exception as exc:
            logger.exception("mcp_discovery_failed server_id=%s", server_id)
            raise McpDiscoveryError("mcp_connection_failed", "Не удалось подключиться к MCP-серверу", 502) from exc
