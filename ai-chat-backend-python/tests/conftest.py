from pathlib import Path

import pytest

from app.agents.registry import AgentRegistry
from app.core.settings import Settings


BACKEND_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def profiles_dir() -> Path:
    return BACKEND_ROOT / "prompts"


@pytest.fixture
def registry(profiles_dir: Path) -> AgentRegistry:
    return AgentRegistry.load(profiles_dir)


@pytest.fixture
def fallback_settings(profiles_dir: Path) -> Settings:
    return Settings(
        _env_file=None,
        chat_mode="fallback",
        llm_api_key="",
        agent_profiles_dir=profiles_dir,
    )


@pytest.fixture
def llm_settings(profiles_dir: Path) -> Settings:
    return Settings(
        _env_file=None,
        chat_mode="llm",
        llm_api_key="test-key",
        llm_model="deepseek-test",
        agent_profiles_dir=profiles_dir,
    )
