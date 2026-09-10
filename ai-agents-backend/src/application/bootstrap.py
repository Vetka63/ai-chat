"""Composition root: инфраструктура соединяется с независимыми агентами здесь."""

from pathlib import Path

import httpx

from agent_core.contracts import ConversationStore
from agent_core.registry import AgentRegistry
from agents.dialogue.config import DialogueAgentConfig
from agents.dialogue.factory import build_dialogue_agent
from application.settings import Settings
from infrastructure.deepseek import DeepSeekClient, DemoClient
from infrastructure.completions import ChatCompletionsClient
from infrastructure.model_catalog import ModelCatalog, ProviderRouter
from infrastructure.tokenizers import ByteEstimate, MistralEstimate
from capabilities.token_accounting.service import TokenAccounting
from capabilities.context_memory.service import ContextMemory, LlmSummarizer


def build_registry(
    settings: Settings,
    http: httpx.AsyncClient,
    store: ConversationStore,
    catalog: ModelCatalog | None = None,
    usage_repository=None,
    summary_repository=None,
) -> AgentRegistry:
    """Создаёт реестр; HTTP-маршруты не знают о DeepSeek и промптах."""

    llm = ProviderRouter(catalog, {
        "deepseek": DemoClient() if settings.mode == "demo" else DeepSeekClient(http, settings.api_key.get_secret_value(), settings.base_url),
        "mistral": DemoClient() if settings.mode == "demo" else ChatCompletionsClient(http, settings.mistral_api_key.get_secret_value(), "mistral", settings.mistral_base_url),
    })
    accounting = TokenAccounting({"deepseek": ByteEstimate(), "mistral":
        MistralEstimate(settings.mistral_tokenizer_path) if settings.mistral_tokenizer_path else ByteEstimate()}, usage_repository)
    prompt_path = Path(__file__).resolve().parents[1] / "agents" / "dialogue" / "prompts" / "system.txt"
    config = DialogueAgentConfig(
        model=settings.model,
        system_prompt=prompt_path.read_text(encoding="utf-8").strip(),
    )
    memory = ContextMemory(summary_repository, LlmSummarizer(llm, accounting, catalog)) if summary_repository else None
    return AgentRegistry([build_dialogue_agent(config, llm, store, catalog=catalog, accounting=accounting, memory=memory)])

