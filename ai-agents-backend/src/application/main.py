"""FastAPI-вход: маршрутизация делегирует всю логику выбранному агенту."""

import logging
from pathlib import Path
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response

from agent_core.contracts import ConversationStore
from agent_core.models import AgentCommand, AgentError, AgentResult, Conversation, ConversationSummary, PreviewCommand
from agent_core.registry import AgentRegistry
from application.api_models import CreateConversation, SelectModel
from application.bootstrap import build_registry
from application.settings import Settings
from infrastructure.sqlite_store import SqliteConversationStore
from infrastructure.sqlite_usage import SqliteUsageRepository
from infrastructure.model_catalog import ModelCatalog
from infrastructure.sqlite_summary import SqliteSummaryRepository
from capabilities.context_memory.models import ContextSettings
from capabilities.token_accounting.service import calculate_token_savings

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
        app.state.usage = SqliteUsageRepository(active_store)
        await app.state.usage.initialize()
        app.state.summaries = SqliteSummaryRepository(active_store)
        await app.state.summaries.initialize()
        app.state.catalog = ModelCatalog.load(Path(__file__).with_name("models.json"), {
            "deepseek": resolved.mode == "demo" or bool(resolved.api_key.get_secret_value()),
            "mistral": resolved.mode == "demo" or bool(resolved.mistral_api_key.get_secret_value()),
        })
        if registry is not None:
            app.state.registry = registry
            yield
            return
        async with httpx.AsyncClient(
            base_url=resolved.base_url.rstrip("/") + "/",
            timeout=httpx.Timeout(resolved.request_timeout_seconds, connect=10),
        ) as http:
            app.state.registry = build_registry(resolved, http, active_store, app.state.catalog, app.state.usage, app.state.summaries)
            yield

    app = FastAPI(title="AI Agents · День 9", version="0.4.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved.origins,
        allow_methods=["GET", "POST", "DELETE", "PATCH"],
        allow_headers=["Content-Type"],
    )

    @app.exception_handler(AgentError)
    async def agent_error(_: Request, exc: AgentError):
        return JSONResponse(status_code=exc.status, content={"code": exc.code, "error": exc.message, "provider_status": exc.provider_status})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError):
        logger.info("invalid_request count=%s", len(exc.errors()))
        return JSONResponse(
            status_code=422,
            content={"code": "invalid_request", "error": "Проверьте формат сообщения"},
        )

    @app.get("/health")
    async def health():
        return {"status": "ok", "mode": resolved.mode, "day": 9}

    @app.get("/api/v1/models")
    async def models(request: Request):
        return {"models": list(request.app.state.catalog.models.values()), "default_model_id": resolved.model}

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
        created = await request.app.state.store.create(agent_id, body.title)
        await request.app.state.store.configure_context(agent_id, created.id, body.context_settings)
        return created.model_copy(update={"context_settings": body.context_settings})

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
        result = await request.app.state.store.get(agent_id, conversation_id)
        result.runs = await request.app.state.usage.list(agent_id, conversation_id)
        result.summary = await request.app.state.summaries.get(agent_id, conversation_id)
        result.token_savings = calculate_token_savings(result.runs)
        return result

    @app.patch("/api/v1/agents/{agent_id}/conversations/{conversation_id}/context")
    async def configure_context(agent_id: str, conversation_id: str, body: ContextSettings, request: Request):
        agent = request.app.state.registry.get(agent_id)
        method = getattr(agent, "configure_context", None)
        if method is None:
            raise AgentError("context_not_supported", "Агент не поддерживает настройку контекста", 501)
        await method(conversation_id, body)
        return body

    @app.post("/api/v1/agents/{agent_id}/conversations/{conversation_id}/fork", response_model=ConversationSummary, status_code=201)
    async def fork(agent_id: str, conversation_id: str, body: ContextSettings, request: Request):
        agent = request.app.state.registry.get(agent_id)
        method = getattr(agent, "fork_conversation", None)
        if method is None:
            raise AgentError("context_not_supported", "Агент не поддерживает копирование контекста", 501)
        return await method(conversation_id, body)

    @app.patch("/api/v1/agents/{agent_id}/conversations/{conversation_id}/model")
    async def select_model(agent_id: str, conversation_id: str, body: SelectModel, request: Request):
        request.app.state.registry.get(agent_id)
        request.app.state.catalog.get(body.model_id)
        await request.app.state.store.select_model(agent_id, conversation_id, body.model_id)
        return {"selected_model_id": body.model_id}

    @app.post("/api/v1/agents/{agent_id}/preview")
    async def preview(agent_id: str, command: PreviewCommand, request: Request):
        agent = request.app.state.registry.get(agent_id)
        preview_method = getattr(agent, "preview", None)
        if preview_method is None:
            raise AgentError("accounting_disabled", "У этого агента не подключён учёт токенов", 501)
        return await preview_method(command)

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
