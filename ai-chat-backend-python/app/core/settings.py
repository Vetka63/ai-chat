from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


BACKEND_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    chat_mode: Literal["fallback", "llm"] = "fallback"
    llm_base_url: str = "https://api.deepseek.com"
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = "deepseek-v4-flash"
    agent_profiles_dir: Path = BACKEND_ROOT / "prompts"
    default_agent_id: str = "general"
    cors_allowed_origins: str = "http://localhost:5173,http://localhost:8080"
    llm_connect_timeout_seconds: float = Field(default=10.0, gt=0)
    llm_read_timeout_seconds: float = Field(default=120.0, gt=0)

    @property
    def allowed_origins(self) -> list[str]:
        return [
            origin.strip()
            for origin in self.cors_allowed_origins.split(",")
            if origin.strip()
        ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
