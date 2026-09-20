"""Явная настройка обязательных правил конкретной задачи."""
from fastapi import APIRouter, Request

from agent_core.models import AgentError
from capabilities.invariants.models import InvariantWorkspace, SaveInvariants

router = APIRouter(prefix='/api/v1/agents/{agent_id}/conversations/{conversation_id}/invariants',
    tags=['invariants'])


def repository(request, agent_id):
    agent = request.app.state.registry.get(agent_id)
    if 'invariants' not in agent.info.capabilities:
        raise AgentError('invariants_not_supported', 'У этого агента нет обязательных правил', 404)
    return request.app.state.invariants, agent


@router.get('', response_model=InvariantWorkspace)
async def get_invariants(agent_id: str, conversation_id: str, request: Request):
    repo, _ = repository(request, agent_id)
    return await repo.workspace(agent_id, conversation_id)


@router.put('', response_model=InvariantWorkspace)
async def save_invariants(agent_id: str, conversation_id: str, body: SaveInvariants, request: Request):
    repo, agent = repository(request, agent_id)
    agent.invariants.policy.validate_rules(body.rules)
    return await repo.save(agent_id, conversation_id, body)
