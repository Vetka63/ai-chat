"""Сменный источник игр: генератор сегодня, IGDB-адаптер в будущем."""

from dataclasses import dataclass
from typing import Protocol
import random


@dataclass(frozen=True)
class Game:
    external_id: str
    title: str
    genre: str
    description: str


class GameSource(Protocol):
    name: str

    def fetch(self, known_ids: set[str]) -> Game | None: ...


class GeneratedGameSource:
    """Выбирает ещё не добавленную вымышленную игру; сеть не требуется."""

    name = "generated"
    _games = (
        Game("g01", "Лунный сад", "Приключение", "Выращивайте растения на заброшенной орбитальной станции."),
        Game("g02", "Тихий маяк", "Головоломка", "Восстанавливайте сигналы маяков на туманных островах."),
        Game("g03", "Картограф облаков", "Стратегия", "Прокладывайте воздушные маршруты между городами."),
        Game("g04", "Часовщик леса", "Симулятор", "Чините механизмы в сказочном заповеднике."),
        Game("g05", "Океан из бумаги", "Приключение", "Откройте тайны книжного моря."),
        Game("g06", "Звёздная ферма", "Симулятор", "Управляйте маленькой фермой на далёкой планете."),
        Game("g07", "Поезд на рассвете", "Повествование", "Знакомьтесь с пассажирами бесконечного поезда."),
        Game("g08", "Пиксельный заповедник", "Песочница", "Собирайте экосистему из цифровых существ."),
        Game("g09", "Музыка подземелий", "Ритм-игра", "Находите выход с помощью мелодий."),
        Game("g10", "Архив ветров", "Головоломка", "Собирайте утраченные истории в городе воздушных мельниц."),
    )

    def __init__(self, rng: random.Random | None = None):
        self.rng = rng or random.Random()

    def fetch(self, known_ids: set[str]) -> Game | None:
        available = [game for game in self._games if game.external_id not in known_ids]
        return self.rng.choice(available) if available else None
