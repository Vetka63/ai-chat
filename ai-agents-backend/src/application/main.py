"""FastAPI-вход: маршрутизация делегирует всю логику выбранному агенту."""

import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from agent_core.contracts import ConversationStore
from agent_core.models import AgentCommand, AgentError, AgentResult, Conversation, ConversationSummary
from agent_core.registry import AgentRegistry
from application.api_models import CreateConversation
from application.bootstrap import build_registry
from application.settings import Settings
from infrastructure.sqlite_store import SqliteConversationStore

logger = logging.getLogger(__name__)


def create_app(
    settings: Settings | None = None,
    registry: AgentRegistry | None = None,
    store: ConversationStore | None = None,
) -> FastAPI:
    """Создаёт приложение и допускает подмену реестра в тестах."""

    resolved = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        active_store = store or SqliteConversationStore(resolved.database_path)
        await active_store.initialize()
        app.state.store = active_store
        if registry is not None:
            app.state.registry = registry
            yield
            return
        async with httpx.AsyncClient(
            base_url=resolved.base_url.rstrip("/") + "/",
            timeout=httpx.Timeout(resolved.request_timeout_seconds, connect=10),
        ) as http:
            app.state.registry = build_registry(resolved, http, active_store)
            yield

    app = FastAPI(title="AI Agents · День 7", version="0.2.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved.origins,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Content-Type"],
    )

    @app.exception_handler(AgentError)
    async def agent_error(_: Request, exc: AgentError):
        return JSONResponse(status_code=exc.status, content={"code": exc.code, "error": exc.message})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError):
        logger.info("invalid_request errors=%s", exc.errors())
        return JSONResponse(
            status_code=422,
            content={"code": "invalid_request", "error": "Проверьте формат сообщения"},
        )

    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": resolved.mode, "day": 7}

    @app.get("/api/v1/agents")
    async def agents(request: Request):
        return {"agents": request.app.state.registry.list()}

    @app.post(
        "/api/v1/agents/{agent_id}/conversations",
        response_model=ConversationSummary,
        status_code=201,
    )
    async def create_conversation(agent_id: str, body: CreateConversation, request: Request):
        request.app.state.registry.get(agent_id)
        return await request.app.state.store.create(agent_id, body.title)

    @app.get(
        "/api/v1/agents/{agent_id}/conversations",
        response_model=list[ConversationSummary],
    )
    async def conversations(agent_id: str, request: Request):
        request.app.state.registry.get(agent_id)
        return await request.app.state.store.list(agent_id)

    @app.get(
        "/api/v1/agents/{agent_id}/conversations/{conversation_id}",
        response_model=Conversation,
    )
    async def conversation(agent_id: str, conversation_id: str, request: Request):
        request.app.state.registry.get(agent_id)
        return await request.app.state.store.get(agent_id, conversation_id)

    @app.delete(
        "/api/v1/agents/{agent_id}/conversations/{conversation_id}",
        status_code=204,
    )
    async def delete_conversation(agent_id: str, conversation_id: str, request: Request):
        request.app.state.registry.get(agent_id)
        await request.app.state.store.delete(agent_id, conversation_id)
        return Response(status_code=204)

    @app.post("/api/v1/agents/{agent_id}/runs", response_model=AgentResult)
    async def run(agent_id: str, command: AgentCommand, request: Request):
        agent = request.app.state.registry.get(agent_id)
        return await agent.run(command)

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
app = create_app()
