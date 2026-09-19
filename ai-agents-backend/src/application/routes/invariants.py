"""Явное редактирование обязательных правил; чат сам не изменяет их."""
from fastapi import APIRouter, Request
from agent_core.models import AgentError
from capabilities.invariants.models import SaveInvariants

router = APIRouter(prefix='/api/v1/agents/{agent_id}/conversations/{conversation_id}/invariants', tags=['invariants'])


def agent_for(request, agent_id):
    agent = request.app.state.registry.get(agent_id)
    if 'invariants' not in agent.info.capabilities:
        raise AgentError('invariants_not_supported', 'У этого агента нет обязательных правил')
    return agent


@router.get('')
async def get_rules(agent_id: str, conversation_id: str, request: Request):
    agent = agent_for(request, agent_id)
    return (await agent.memory.repository.workspace(agent_id, conversation_id)).invariants


@router.put('')
async def save_rules(agent_id: str, conversation_id: str, command: SaveInvariants, request: Request):
    agent = agent_for(request, agent_id)
    return await agent.invariants.repository.save(agent_id, conversation_id, command, agent.invariants.policy)
