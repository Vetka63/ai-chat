"""Правила, определяющие допустимые записи алгоритмического наставника."""
from pydantic import ValidationError
from agent_core.models import AgentError
from .models import ProblemSpec

WORKING_KEYS = {'goal', 'approach', 'plan', 'solution', 'test_cases', 'findings', 'constraints'}


class AlgorithmMemoryPolicy:
    """Рабочие поля фиксированы; долгосрочные заметки имеют пользовательские названия."""
    def validate_entry(self, candidate):
        if candidate.layer == 'working' and candidate.key not in WORKING_KEYS:
            raise AgentError('invalid_memory_key', 'Выберите разрешённое поле рабочей памяти')

    def validate_problem(self, problem):
        try:
            return ProblemSpec.model_validate(problem).model_dump()
        except ValidationError as exc:
            raise AgentError('invalid_problem', 'Проверьте поля карточки задачи') from exc
