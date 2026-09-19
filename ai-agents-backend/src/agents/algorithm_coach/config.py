"""Неизменяемая конфигурация алгоритмического агента."""
from pydantic import BaseModel, ConfigDict, Field


class AlgorithmCoachConfig(BaseModel):
    """Правила экземпляра без состояния конкретного пользователя или задачи."""
    model_config = ConfigDict(extra='forbid', frozen=True)
    id: str = 'algorithm_coach'
    name: str = 'Алгоритмический наставник'
    description: str = 'Дни 11–14 · память, этапы и обязательные правила'
    model: str
    temperature: float = Field(default=0.4, ge=0, le=2)
    system_prompt: str
    proposals_prompt: str
