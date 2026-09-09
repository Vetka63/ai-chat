"""Подготовка входа, принадлежащая диалоговому агенту."""


class TrimInputPolicy:
    """Удаляет внешние пробелы, не меняя смысл пользовательского текста."""

    def prepare(self, text: str) -> str:
        return text.strip()

