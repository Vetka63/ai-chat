"""HTTP-команды автомата конкретной задачи алгоритмического наставника."""
from fastapi import APIRouter, Request

from agent_core.models import AgentError
from capabilities.task_workflow.models import WorkflowCommand, WorkflowWorkspace

router = APIRouter(prefix='/api/v1/agents/{agent_id}/conversations/{conversation_id}/workflow', tags=['workflow'])


def repository(request, agent_id):
    agent = request.app.state.registry.get(agent_id)
    if 'task_workflow' not in agent.info.capabilities:
        raise AgentError('workflow_not_supported', 'У этого агента нет автомата задачи', 404)
    return request.app.state.workflow


@router.get('', response_model=WorkflowWorkspace)
async def get_workflow(agent_id: str, conversation_id: str, request: Request):
    return await repository(request, agent_id).workspace(agent_id, conversation_id)


@router.post('/actions', response_model=WorkflowWorkspace)
async def apply_workflow(agent_id: str, conversation_id: str, command: WorkflowCommand, request: Request):
    agent = request.app.state.registry.get(agent_id)
    handler = getattr(agent, 'apply_workflow', None)
    if handler is not None:
        return await handler(conversation_id, command)
    return await repository(request, agent_id).apply(agent_id, conversation_id, command)
