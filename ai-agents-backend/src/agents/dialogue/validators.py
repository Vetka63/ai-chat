"""Валидаторы, специфичные для диалогового агента."""

from agent_core.models import AgentError


class NonEmptyInputValidator:
    """Не допускает пустой запрос к платному LLM API."""

    def validate(self, text: str) -> None:
        if not text:
            raise AgentError("empty_input", "Введите сообщение")


class InputSizeValidator:
    """Ограничивает размер одного пользовательского сообщения."""

    def __init__(self, maximum: int):
        self.maximum = maximum

    def validate(self, text: str) -> None:
        if len(text) > self.maximum:
            raise AgentError("input_too_long", f"Сообщение длиннее {self.maximum} символов")


class NonEmptyOutputValidator:
    """Не выдаёт пустой ответ модели как успешный результат."""

    def validate(self, text: str) -> None:
        if not text.strip():
            raise AgentError("empty_output", "Модель вернула пустой ответ", 502)

