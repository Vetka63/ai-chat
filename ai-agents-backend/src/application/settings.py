"""Настройки процесса из переменных окружения; секреты не входят в агента."""

from typing import Literal

from pydantic import AliasChoices, Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Конфигурация Python-сервиса Day 6."""

    model_config = SettingsConfigDict(
        env_prefix="PY_AGENT_",
        env_file=("../.env", ".env"),
        env_ignore_empty=True,
        extra="ignore",
    )

    mode: Literal["llm", "demo"] = "llm"
    api_key: SecretStr = Field(
        default=SecretStr(""),
        validation_alias=AliasChoices("PY_AGENT_API_KEY", "LLM_API_KEY"),
    )
    base_url: str = Field(
        default="https://api.deepseek.com",
        validation_alias=AliasChoices("PY_AGENT_BASE_URL", "LLM_BASE_URL"),
    )
    model: str = Field(
        default="deepseek-v4-flash",
        validation_alias=AliasChoices("PY_AGENT_MODEL", "LLM_MODEL"),
    )
    request_timeout_seconds: float = Field(default=90, gt=0, le=300)
    cors_origins: str = "http://localhost:5174,http://localhost:8083"

    @property
    def origins(self) -> list[str]:
        return [value.strip() for value in self.cors_origins.split(",") if value.strip()]

