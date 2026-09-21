"""Контракт состояния алгоритмической задачи и явных действий пользователя."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


Phase = Literal['planning', 'execution', 'validation', 'done']
Action = Literal['pause', 'resume', 'accept_plan', 'accept_solution', 'accept_validation',
                 'select_step', 'request_changes', 'request_replan', 'invariants_changed',
                 'problem_changed']
CommandAction = Literal['pause', 'resume', 'accept_plan', 'accept_solution', 'accept_validation',
                        'select_step', 'request_changes', 'request_replan']


class WorkflowModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class WorkflowState(WorkflowModel):
    phase: Phase
    status: Literal['active', 'paused']
    current_step_id: str | None
    candidate_message_id: int | None
    expected_action: str
    revision: int


class ArtifactReference(WorkflowModel):
    id: str
    revision: int


class WorkflowControl(WorkflowModel):
    """Версии артефактов, которые сейчас разрешают следующий этап."""
    approved_plan: ArtifactReference | None = None
    current_solution: ArtifactReference | None = None
    current_validation: ArtifactReference | None = None
    approved_task_revision: int | None = None
    approved_invariant_revision: int | None = None
    validation_solution_id: str | None = None
    change_request: str | None = None


class TransitionOption(WorkflowModel):
    """Доступность команды, вычисленная сервером из сохранённого состояния."""
    action: CommandAction
    target_phase: Phase
    allowed: bool
    reason: str | None = None


class WorkflowArtifact(WorkflowModel):
    id: str
    kind: Literal['plan', 'solution', 'validation']
    revision: int
    content: dict
    based_on_artifact_id: str | None = None
    task_revision: int | None = None
    invariant_revision: int | None = None
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
    task_revision: int
    invariant_revision: int
    state: WorkflowState
    control: WorkflowControl
    transitions: list[TransitionOption]
    candidate_text: str | None
    artifacts: list[WorkflowArtifact]
    events: list[WorkflowEvent]


class WorkflowCommand(WorkflowModel):
    action: CommandAction
    expected_revision: int = Field(ge=1)
    content: dict | None = None
    step_id: str | None = None
