from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.agents.registry import AgentRegistry
from app.api.routes import router
from app.core.settings import Settings, get_settings
from app.domain.errors import ChatError
from app.llm.deepseek import DeepSeekClient, LlmClient
from app.services.chat import ChatOrchestrator


def create_app(
    settings: Settings | None = None,
    agent_registry: AgentRegistry | None = None,
    llm_client: LlmClient | None = None,
) -> FastAPI:
    resolved_settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        registry = agent_registry or AgentRegistry.load(
            resolved_settings.agent_profiles_dir
        )
        registry.get(resolved_settings.default_agent_id)
        owned_http_client: httpx.AsyncClient | None = None
        provider_client = llm_client
        if provider_client is None:
            timeout = httpx.Timeout(
                connect=resolved_settings.llm_connect_timeout_seconds,
                read=resolved_settings.llm_read_timeout_seconds,
                write=30.0,
                pool=10.0,
            )
            owned_http_client = httpx.AsyncClient(
                base_url=resolved_settings.llm_base_url.rstrip("/"),
                timeout=timeout,
            )
            provider_client = DeepSeekClient(resolved_settings, owned_http_client)

        app.state.agent_registry = registry
        app.state.chat_orchestrator = ChatOrchestrator(
            resolved_settings,
            registry,
            provider_client,
        )
        yield
        if owned_http_client is not None:
            await owned_http_client.aclose()

    app = FastAPI(
        title="AI Chat Backend",
        version="1.0.0",
        lifespan=lifespan,
    )
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved_settings.allowed_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Content-Type"],
    )
    app.include_router(router)

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.exception_handler(ChatError)
    async def chat_error_handler(
        _request: Request,
        exc: ChatError,
    ) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": exc.public_message},
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _request: Request,
        exc: RequestValidationError,
    ) -> JSONResponse:
        first_error = exc.errors()[0] if exc.errors() else {}
        message = first_error.get("msg", "Invalid request")
        return JSONResponse(status_code=400, content={"error": message})

    return app


app = create_app()
