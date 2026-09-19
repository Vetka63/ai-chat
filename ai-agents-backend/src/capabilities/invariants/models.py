"""Обязательные правила задачи и проверяемые решения, отдельно от переписки."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from capabilities.task_workflow.models import WorkflowCommand

Label = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)]
RuleText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
Verdict = Literal['pass', 'conflict', 'uncertain']


class InvariantModel(BaseModel):
    """Строгая схема границ модуля."""
    model_config = ConfigDict(extra='forbid')


class RuleDraft(InvariantModel):
    """Явно редактируемое правило; ID назначает сервер."""
    id: str | None = Field(default=None, min_length=1, max_length=64)
    kind: Literal['language', 'signature', 'allowed_imports', 'semantic']
    label: Label
    value: RuleText
    active: bool = True


class TaskInvariant(RuleDraft):
    """Сохранённое правило конкретной задачи с версией и автором."""
    id: str
    revision: int
    author: Literal['user'] = 'user'


class SaveInvariants(WorkflowCommand):
    """Атомарная замена списка правил с проверкой версии автомата."""
    rules: list[RuleDraft] = Field(max_length=20)


class RuleVerdict(InvariantModel):
    """Результат проверки одного правила; причина обязательна и ограничена."""
    rule_id: str
    verdict: Verdict
    reason: RuleText


class JudgePayload(InvariantModel):
    """Judge обязан вернуть ровно одну оценку каждого активного ID."""
    checks: list[RuleVerdict] = Field(min_length=1, max_length=20)


class InvariantCheck(InvariantModel):
    """Аудит без отклонённого текста кандидата; snapshot правил сохраняет смысл версии."""
    id: str
    command_id: str
    stage: Literal['input', 'output', 'artifact']
    revision: int
    verdict: Verdict
    checks: list[RuleVerdict]
    rules: list[TaskInvariant]
    run_id: str | None = None
    error_code: str | None = None
    created_at: str


class InvariantWorkspace(InvariantModel):
    """Текущие правила и ограниченный хвост аудита; полный аудит остаётся в БД."""
    revision: int = 1
    rules: list[TaskInvariant] = Field(default_factory=list)
    checks: list[InvariantCheck] = Field(default_factory=list)
