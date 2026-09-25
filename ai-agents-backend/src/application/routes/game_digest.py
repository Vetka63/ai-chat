"""HTTP-управление отдельным чатом периодических сводок."""

from fastapi import APIRouter, Request
from typing import Literal

from pydantic import BaseModel

router = APIRouter(prefix="/api/v1/agents/game_digest/conversations", tags=["game-digest"])


class ScheduleRequest(BaseModel):
    interval_seconds: Literal[1800] = 1800


def agent(request: Request):
    return request.app.state.registry.get("game_digest")


@router.get("/{conversation_id}/schedule")
async def status(conversation_id: str, request: Request):
    return await agent(request).status(conversation_id)


@router.put("/{conversation_id}/schedule")
async def schedule(conversation_id: str, body: ScheduleRequest, request: Request):
    return await agent(request).schedule(conversation_id, body.interval_seconds)


@router.delete("/{conversation_id}/schedule")
async def cancel(conversation_id: str, request: Request):
    return await agent(request).cancel(conversation_id)


@router.get("/{conversation_id}/reports")
async def reports(conversation_id: str, request: Request):
    return await agent(request).reports(conversation_id)
