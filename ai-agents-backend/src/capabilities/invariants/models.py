"""Контракты обязательных правил, изолированных от сообщений и мягкого профиля."""
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


Label = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=120)]
RuleText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=2000)]
Reason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=800)]
Verdict = Literal['pass', 'conflict', 'uncertain']


class StrictModel(BaseModel):
    model_config = ConfigDict(extra='forbid')


class RuleDraft(StrictModel):
    """Явно введённое пользователем правило; ID нового правила выдаёт сервер."""
    id: str | None = Field(default=None, min_length=1, max_length=64)
    kind: Literal['semantic', 'language'] = 'semantic'
    label: Label
    value: RuleText
    active: bool = True


class TaskInvariant(RuleDraft):
    id: str
    revision: int = Field(ge=1)


class SaveInvariants(StrictModel):
    expected_revision: int = Field(ge=1)
    rules: list[RuleDraft] = Field(max_length=20)


class RuleVerdict(StrictModel):
    rule_id: str
    verdict: Verdict
    reason: Reason


class JudgePayload(StrictModel):
    checks: list[RuleVerdict] = Field(min_length=1, max_length=20)


class InvariantCheck(StrictModel):
    id: str
    stage: Literal['input', 'output', 'artifact']
    revision: int
    verdict: Verdict
    checks: list[RuleVerdict]
    rules: list[TaskInvariant]
    run_id: str | None = None
    error_code: str | None = None
    created_at: str


class InvariantWorkspace(StrictModel):
    revision: int = 1
    rules: list[TaskInvariant] = Field(default_factory=list)
    checks: list[InvariantCheck] = Field(default_factory=list)
