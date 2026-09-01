from fastapi import APIRouter, Request

from app.domain.agents import AgentSummary
from app.domain.chat import ChatRequest, ChatResponse


router = APIRouter(prefix="/api")


@router.get("/profiles", response_model=list[AgentSummary])
async def profiles(request: Request) -> list[AgentSummary]:
    return request.app.state.agent_registry.list_public()


@router.post("/chat", response_model=ChatResponse, response_model_by_alias=True)
async def chat(request_body: ChatRequest, request: Request) -> ChatResponse:
    return await request.app.state.chat_orchestrator.reply(request_body)
