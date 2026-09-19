"""Типизированные настройки персонализации, независимые от конкретного агента."""
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StringConstraints

ProfileName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=80)]
SoftConstraint = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=400)]


class ProfileModel(BaseModel):
    """Запрещает незаявленные поля, в том числе подмену ID и системного промпта."""
    model_config = ConfigDict(extra='forbid')


class ProfilePreferences(ProfileModel):
    """Мягкие настройки подачи; не инварианты задачи и не правила доступа."""
    explanation_language: Literal['ru', 'en'] = 'ru'
    experience_level: Literal['beginner', 'intermediate', 'advanced'] = 'intermediate'
    detail_level: Literal['concise', 'balanced', 'detailed'] = 'balanced'
    response_format: Literal['prose', 'bullets', 'steps'] = 'prose'
    preferred_code_language: Literal['python', 'java', 'javascript', 'go'] | None = None
    soft_constraints: list[SoftConstraint] = Field(default_factory=list, max_length=8)


class UserProfile(ProfileModel):
    """Профиль локальной персоны с отдельными версиями предпочтений и памяти."""
    id: str
    name: str
    preferences: ProfilePreferences = Field(default_factory=ProfilePreferences)
    revision: int = 1
    memory_revision: int = 1
    updated_at: str = ''


class CreateProfile(ProfileModel):
    """Команда явного создания профиля, без переноса чужой памяти."""
    name: ProfileName
    preferences: ProfilePreferences = Field(default_factory=ProfilePreferences)


class UpdateProfile(CreateProfile):
    """Полная замена настроек с ожидаемой версией для защиты от потери правок."""
    revision: int = Field(ge=1, strict=True)
