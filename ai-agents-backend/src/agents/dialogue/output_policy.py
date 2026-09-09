"""Подготовка результата, принадлежащая диалоговому агенту."""


class PlainTextOutputPolicy:
    """Возвращает чистый текст ответа без провайдерной обёртки."""

    def present(self, text: str) -> str:
        return text.strip()

