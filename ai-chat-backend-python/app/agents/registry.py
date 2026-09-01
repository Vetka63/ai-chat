from pathlib import Path

import yaml
from pydantic import ValidationError

from app.domain.agents import AgentProfile, AgentSummary
from app.domain.errors import AgentNotFoundError


class AgentRegistryError(RuntimeError):
    pass


class AgentRegistry:
    def __init__(self, profiles: dict[str, AgentProfile]):
        self._profiles = profiles

    @classmethod
    def load(cls, directory: Path) -> "AgentRegistry":
        if not directory.is_dir():
            raise AgentRegistryError(f"Agent profiles directory does not exist: {directory}")

        profiles: dict[str, AgentProfile] = {}
        for path in sorted(directory.glob("*.yaml")):
            try:
                raw = yaml.safe_load(path.read_text(encoding="utf-8"))
                profile = AgentProfile.model_validate(raw)
            except (OSError, yaml.YAMLError, ValidationError) as exc:
                raise AgentRegistryError(f"Invalid agent profile {path.name}: {exc}") from exc

            if not profile.enabled:
                continue
            if profile.id in profiles:
                raise AgentRegistryError(f"Duplicate agent profile id: {profile.id}")
            profiles[profile.id] = profile

        if not profiles:
            raise AgentRegistryError(f"No enabled agent profiles found in {directory}")
        return cls(profiles)

    def get(self, agent_id: str) -> AgentProfile:
        try:
            return self._profiles[agent_id]
        except KeyError as exc:
            raise AgentNotFoundError(agent_id) from exc

    def list_public(self) -> list[AgentSummary]:
        return [
            AgentSummary.from_profile(profile)
            for profile in self._profiles.values()
        ]
