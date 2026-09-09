"""Composition root: инфраструктура соединяется с независимыми агентами здесь."""

from pathlib import Path

import httpx

from agent_core.registry import AgentRegistry
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.settings import Settings
from infrastructure.deepseek import DeepSeekClient, DemoClient


def build_registry(settings: Settings, http: httpx.AsyncClient) -> AgentRegistry:
    """Создаёт реестр; HTTP-маршруты не знают о DeepSeek и промптах."""

    llm = (
        DemoClient()
        if settings.mode == "demo"
        else DeepSeekClient(http, settings.api_key.get_secret_value())
    )
    prompt_path = Path(__file__).resolve().parents[1] / "agents" / "dialogue" / "prompts" / "system.txt"
    config = DialogueAgentConfig(
        model=settings.model,
        system_prompt=prompt_path.read_text(encoding="utf-8").strip(),
    )
    return AgentRegistry([build_dialogue_agent(config, llm)])

