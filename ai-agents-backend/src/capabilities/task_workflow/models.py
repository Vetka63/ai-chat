"""Контракт состояния алгоритмической задачи и явных действий пользователя."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Phase = Literal['planning', 'execution', 'validation', 'done']
Action = Literal['pause', 'resume', 'accept_plan', 'accept_solution', 'accept_validation',
                 'select_step', 'invariants_changed']
CommandAction = Literal['pause', 'resume', 'accept_plan', 'accept_solution', 'accept_validation', 'select_step']


class WorkflowModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class WorkflowState(WorkflowModel):
    phase: Phase
    status: Literal['active', 'paused']
    current_step_id: str | None
    candidate_message_id: int | None
    expected_action: str
    revision: int


class WorkflowArtifact(WorkflowModel):
    id: str
    kind: Literal['plan', 'solution', 'validation']
    revision: int
    content: dict
    source_message_id: int | None
    created_at: str


class WorkflowEvent(WorkflowModel):
    action: Action
    from_phase: Phase
    to_phase: Phase
    from_status: Literal['active', 'paused']
    to_status: Literal['active', 'paused']
    revision: int
    created_at: str


class WorkflowWorkspace(WorkflowModel):
    task_id: str
    state: WorkflowState
    candidate_text: str | None
    artifacts: list[WorkflowArtifact]
    events: list[WorkflowEvent]


class WorkflowCommand(WorkflowModel):
    action: CommandAction
    expected_revision: int = Field(ge=1)
    content: dict | None = None
    step_id: str | None = None
