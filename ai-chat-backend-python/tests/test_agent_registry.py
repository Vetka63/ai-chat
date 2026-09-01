from pathlib import Path

import pytest

from app.agents.registry import AgentRegistry, AgentRegistryError
from app.domain.errors import AgentNotFoundError


def test_loads_two_public_profiles_without_system_prompts(
    registry: AgentRegistry,
) -> None:
    summaries = registry.list_public()

    assert [summary.id for summary in summaries] == ["general", "recipe"]
    assert summaries[0].name == "Обычный чат"
    assert "system_prompt" not in summaries[0].model_dump()


def test_recipe_and_general_use_different_system_prompts(
    registry: AgentRegistry,
) -> None:
    assert registry.get("general").system_prompt != registry.get("recipe").system_prompt


def test_unknown_profile_is_rejected(registry: AgentRegistry) -> None:
    with pytest.raises(AgentNotFoundError):
        registry.get("missing")


def test_invalid_profile_fails_registry_startup(tmp_path: Path) -> None:
    (tmp_path / "invalid.yaml").write_text("id: invalid", encoding="utf-8")

    with pytest.raises(AgentRegistryError, match="Invalid agent profile"):
        AgentRegistry.load(tmp_path)
