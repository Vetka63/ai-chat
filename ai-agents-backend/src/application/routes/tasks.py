"""Явные команды жизненного цикла, одинаковые правила для UI и прямого API."""
from fastapi import APIRouter, Request
from agent_core.models import AgentError
from capabilities.task_workflow.models import WorkflowWorkspace, TransitionCommand, SaveArtifact, SelectStep

router = APIRouter(prefix='/api/v1/agents/{agent_id}/conversations/{conversation_id}/task', tags=['tasks'])


def workflow(request, agent_id):
    agent = request.app.state.registry.get(agent_id)
    if 'task_workflow' not in agent.info.capabilities:
        raise AgentError('workflow_not_supported', 'У этого агента нет автомата задачи')
    return agent.workflow


@router.get('', response_model=WorkflowWorkspace)
async def get_task(agent_id: str, conversation_id: str, request: Request):
    return await workflow(request, agent_id).repository.workspace(agent_id, conversation_id)


@router.post('/events', response_model=WorkflowWorkspace)
async def transition(agent_id: str, conversation_id: str, body: TransitionCommand, request: Request):
    return await workflow(request, agent_id).change(agent_id, conversation_id, 'transition', body)


@router.post('/artifacts', response_model=WorkflowWorkspace)
async def artifact(agent_id: str, conversation_id: str, body: SaveArtifact, request: Request):
    return await workflow(request, agent_id).change(agent_id, conversation_id, 'artifact', body)


@router.post('/step', response_model=WorkflowWorkspace)
async def step(agent_id: str, conversation_id: str, body: SelectStep, request: Request):
    return await workflow(request, agent_id).change(agent_id, conversation_id, 'step', body)
