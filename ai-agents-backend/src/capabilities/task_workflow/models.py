"""Состояние задачи и явные команды; текст LLM не управляет автоматом."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

Text = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=30000)]
Phase = Literal['planning', 'execution', 'validation', 'done']
Event = Literal['start_execution', 'start_validation', 'finish', 'request_changes', 'request_replan', 'pause', 'resume']


class WorkflowModel(BaseModel):
    """Запрещает дополнительные поля на границах команд."""
    model_config = ConfigDict(extra='forbid')


class TaskState(WorkflowModel):
    """Фаза отделена от паузы, шаг ссылается на сохранённый план."""
    phase: Phase = 'planning'
    status: Literal['active', 'paused'] = 'active'
    current_step_id: str | None = None
    expected_action: str = 'save_plan'
    revision: int = 1


class TaskArtifact(WorkflowModel):
    """Неизменяемая версия результата с происхождением и связанными версиями."""
    id: str
    kind: Literal['plan', 'solution', 'validation']
    revision: int
    content: dict
    source_message_id: int | None = None
    task_revision: int
    plan_revision: int | None = None
    solution_revision: int | None = None
    created_at: str


class WorkflowWorkspace(WorkflowModel):
    """Снимок автомата, истории артефактов и событий для UI и prompt."""
    task_id: str
    state: TaskState
    artifacts: list[TaskArtifact] = Field(default_factory=list)
    events: list[dict] = Field(default_factory=list)
    allowed_events: list[str] = Field(default_factory=list)
    active_command_id: str | None = None


class WorkflowCommand(WorkflowModel):
    """Общая защита от повторной команды и устаревшей формы."""
    command_id: str = Field(min_length=1, max_length=64)
    expected_revision: int = Field(ge=1, strict=True)


class TransitionCommand(WorkflowCommand):
    """Именованное событие вместо произвольного PATCH фазы."""
    event: Event


class SaveArtifact(WorkflowCommand):
    """Явное сохранение результата; предметная схема проверяется политикой агента."""
    kind: Literal['plan', 'solution', 'validation']
    content: dict
    source_message_id: int | None = Field(default=None, gt=0)


class SelectStep(WorkflowCommand):
    """Указание шага существующего плана, не отметка о выполнении кода."""
    step_id: str = Field(min_length=1, max_length=64)
