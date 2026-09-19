"""HTTP-команды памяти делегируют запись политикам выбранного агента."""
from fastapi import APIRouter, Request
from agent_core.models import AgentError
from capabilities.memory_layers.models import (
    MemoryVersions, MemoryWorkspace, ProposeMemory, ResolveProposal, SaveMemory, SaveProblem,
)

router = APIRouter(prefix='/api/v1/agents/{agent_id}/conversations/{conversation_id}/memory', tags=['memory'])


def memory_agent(request, agent_id):
    agent = request.app.state.registry.get(agent_id)
    if 'memory_layers' not in agent.info.capabilities:
        raise AgentError('memory_not_supported', 'У этого агента не подключены три слоя памяти', 422)
    return agent


@router.get('', response_model=MemoryWorkspace)
async def workspace(agent_id: str, conversation_id: str, request: Request):
    agent = memory_agent(request, agent_id)
    return await agent.memory.repository.workspace(agent_id, conversation_id)


@router.put('/problem', response_model=MemoryWorkspace)
async def problem(agent_id: str, conversation_id: str, body: SaveProblem, request: Request):
    agent = memory_agent(request, agent_id)
    await agent.memory.save_problem(agent_id, conversation_id, body)
    return await agent.memory.repository.workspace(agent_id, conversation_id)


@router.post('/entries', response_model=MemoryWorkspace)
async def save_entry(agent_id: str, conversation_id: str, body: SaveMemory, request: Request):
    agent = memory_agent(request, agent_id)
    await agent.memory.save(agent_id, conversation_id, body)
    return await agent.memory.repository.workspace(agent_id, conversation_id)


@router.post('/entries/{entry_id}/delete', response_model=MemoryWorkspace)
async def delete_entry(agent_id: str, conversation_id: str, entry_id: str, body: MemoryVersions, request: Request):
    agent = memory_agent(request, agent_id)
    await agent.memory.repository.delete_entry(agent_id, conversation_id, entry_id, body)
    return await agent.memory.repository.workspace(agent_id, conversation_id)


@router.post('/proposals')
async def propose(agent_id: str, conversation_id: str, body: ProposeMemory, request: Request):
    return await memory_agent(request, agent_id).propose_memory(conversation_id, body)


@router.post('/proposals/{proposal_id}', response_model=MemoryWorkspace)
async def resolve(agent_id: str, conversation_id: str, proposal_id: str, body: ResolveProposal, request: Request):
    agent = memory_agent(request, agent_id)
    await agent.memory.repository.resolve(agent_id, conversation_id, proposal_id, body.action, body)
    return await agent.memory.repository.workspace(agent_id, conversation_id)
