"""Подключение к доверенному MCP-серверу и чтение всех страниц tools/list."""

import asyncio
import json
import logging
import sys
from dataclasses import dataclass
from pathlib import Path

from mcp import Client, StdioServerParameters

from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary, McpToolExecution, McpToolSummary

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
    """Хранит публичное описание и доверенный адрес либо команду MCP-сервера.

    Экземпляры создаёт бэкенд. Браузер передаёт только ``summary.id`` и не
    может подменить ``url``, ``command`` или ``args``.
    """

    summary: McpServerSummary
    command: str | None = None
    args: tuple[str, ...] = ()
    url: str | None = None


def default_servers(python_mcp_url: str | None = None,
                    java_mcp_url: str | None = None) -> tuple[McpServerDefinition, ...]:
    """Формирует каталог разрешённых серверов для заданий Дней 16 и 17.

    Учебный сервер Дня 16 доступен для обнаружения; каталог игр Дня 17 можно
    подключить к чату, если задан адрес отдельного MCP-сервиса.

    Returns:
        Неизменяемый набор определений серверов.
    """

    server_file = Path(__file__).with_name("demo_server.py").resolve()
    servers = [
        McpServerDefinition(
            summary=McpServerSummary(
                id="local-demo",
                name="Локальный учебный сервер",
                description="Два простых инструмента для проверки подключения и tools/list.",
            ),
            command=sys.executable,
            args=(str(server_file),),
        ),
    ]
    if python_mcp_url:
        servers.append(McpServerDefinition(
            summary=McpServerSummary(
                id="games-mock",
                name="Каталог игр · Python",
                description="Поиск игр через независимый Python MCP-сервис по HTTP.",
                transport="streamable_http",
                chat_enabled=True,
            ),
            url=python_mcp_url,
        ))
    if java_mcp_url:
        servers.append(McpServerDefinition(
            summary=McpServerSummary(
                id="java-games-mock",
                name="Каталог игр · Java / Spring Boot",
                description="Тот же поиск игр, но через независимый Java MCP-сервис по HTTP.",
                transport="streamable_http",
                chat_enabled=True,
            ),
            url=java_mcp_url,
        ))
    return tuple(servers)


class McpDiscoveryService:
    """Управляет разрешёнными MCP-серверами, обнаружением и вызовом инструментов.

    Для каждого запроса он открывает новое MCP-соединение и закрывает его после
    ``tools/list`` или проверенного ``tools/call``.
    """

    def __init__(self, servers: tuple[McpServerDefinition, ...] | None = None, timeout_seconds: float = 10,
                 python_mcp_url: str | None = None, java_mcp_url: str | None = None):
        """Подготавливает разрешённые серверы и общий таймаут MCP-обмена.

        Args:
            servers: Явный каталог серверов, например для теста. Если не задан,
                используется каталог из ``default_servers``.
            timeout_seconds: Максимальное время на подключение и все страницы
                ``tools/list`` вместе, а не таймаут каждой страницы отдельно.
        """
        definitions = default_servers(python_mcp_url, java_mcp_url) if servers is None else servers
        self._servers = {server.summary.id: server for server in definitions}
        self._timeout_seconds = timeout_seconds

    def list_servers(self) -> list[McpServerSummary]:
        """Возвращает UI только публичные описания разрешённых серверов.

        Команда и аргументы запуска остаются на бэкенде.
        """
        return [definition.summary for definition in self._servers.values()]

    def chat_server(self, server_id: str) -> McpServerSummary:
        """Проверяет, что сервер разрешён для модели в чате, а не только для просмотра."""

        definition = self._servers.get(server_id)
        if definition is None or not definition.summary.chat_enabled:
            raise McpDiscoveryError("mcp_server_not_allowed", "MCP-сервер недоступен для чата", 422)
        return definition.summary

    @staticmethod
    def _connection(definition: McpServerDefinition) -> StdioServerParameters | str:
        """Выбирает доверенный stdio-процесс или адрес независимого HTTP-сервиса."""

        if definition.url:
            return definition.url
        if definition.command:
            return StdioServerParameters(command=definition.command, args=list(definition.args))
        raise McpDiscoveryError("mcp_invalid_config", "MCP-сервер не настроен", 503)

    async def discover(self, server_id: str) -> McpDiscoveryResult:
        """Подключается к выбранному серверу и считывает список инструментов.

        Сначала проверяет ID по каталогу. Затем MCP SDK запускает локальный
        процесс через stdio или обращается к доверенному HTTP-адресу, затем читает
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
                parameters = self._connection(definition)
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

    async def call_tool(self, server_id: str, tool_name: str, arguments: dict) -> McpToolExecution:
        """Выполняет только объявленный инструмент разрешённого чатового сервера."""

        self.chat_server(server_id)
        definition = self._servers[server_id]
        try:
            async with asyncio.timeout(self._timeout_seconds):
                parameters = self._connection(definition)
                async with Client(parameters) as client:
                    cursor: str | None = None
                    seen_cursors: set[str] = set()
                    found = False
                    while True:
                        page = await client.list_tools(cursor=cursor)
                        found = found or any(tool.name == tool_name for tool in page.tools)
                        cursor = page.next_cursor
                        if cursor is None:
                            break
                        if cursor in seen_cursors:
                            raise McpDiscoveryError("mcp_invalid_list", "Список MCP-инструментов некорректен")
                        seen_cursors.add(cursor)
                    if not found:
                        raise McpDiscoveryError("mcp_tool_not_allowed", "Инструмент не объявлен сервером", 422)
                    result = await client.call_tool(tool_name, arguments)
                    if result.is_error or not isinstance(result.structured_content, dict):
                        raise McpDiscoveryError("mcp_tool_failed", "MCP-инструмент не вернул данные", 502)
                    if len(json.dumps(result.structured_content, ensure_ascii=False)) > 12000:
                        raise McpDiscoveryError("mcp_result_too_large", "Ответ MCP-инструмента слишком большой", 502)
                    return McpToolExecution(server_id=server_id, tool_name=tool_name,
                                            structured_content=result.structured_content)
        except McpDiscoveryError:
            raise
        except TimeoutError as exc:
            logger.warning("mcp_tool_timeout server_id=%s tool=%s", server_id, tool_name)
            raise McpDiscoveryError("mcp_timeout", "MCP-сервер не ответил вовремя", 504) from exc
        except Exception as exc:
            logger.exception("mcp_tool_call_failed server_id=%s tool=%s", server_id, tool_name)
            raise McpDiscoveryError("mcp_connection_failed", "Не удалось выполнить MCP-инструмент", 502) from exc
