"""HTTP-маршруты отдельного MCP-раздела дня 16."""

from fastapi import APIRouter, Request

from capabilities.mcp_discovery.models import McpDiscoveryResult, McpServerSummary

router = APIRouter(prefix="/api/v1/mcp", tags=["mcp"])


@router.get("/servers", response_model=list[McpServerSummary])
async def list_servers(request: Request):
    """Показывает только разрешённые бэкендом MCP-серверы."""

    return request.app.state.mcp_discovery.list_servers()


@router.post("/servers/{server_id}/discover", response_model=McpDiscoveryResult)
async def discover_tools(server_id: str, request: Request):
    """Проверяет соединение и возвращает объявленные сервером инструменты."""

    return await request.app.state.mcp_discovery.discover(server_id)
