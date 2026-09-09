"""FastAPI-вход: маршрутизация делегирует всю логику выбранному агенту."""

import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from agent_core.models import AgentCommand, AgentError, AgentResult
from agent_core.registry import AgentRegistry
from application.bootstrap import build_registry
from application.settings import Settings

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, registry: AgentRegistry | None = None) -> FastAPI:
    """Создаёт приложение и допускает подмену реестра в тестах."""

    resolved = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if registry is not None:
            app.state.registry = registry
            yield
            return
        async with httpx.AsyncClient(
            base_url=resolved.base_url.rstrip("/") + "/",
            timeout=httpx.Timeout(resolved.request_timeout_seconds, connect=10),
        ) as http:
            app.state.registry = build_registry(resolved, http)
            yield

    app = FastAPI(title="AI Agents · День 6", version="0.1.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=resolved.origins,
        allow_methods=["GET", "POST"],
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
        return {"status": "ok", "mode": resolved.mode, "day": 6}

    @app.get("/api/v1/agents")
    async def agents(request: Request):
        return {"agents": request.app.state.registry.list()}

    @app.post("/api/v1/agents/{agent_id}/runs", response_model=AgentResult)
    async def run(agent_id: str, command: AgentCommand, request: Request):
        agent = request.app.state.registry.get(agent_id)
        return await agent.run(command)

    return app


logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
app = create_app()

