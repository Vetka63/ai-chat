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
from capabilities.context_memory.facts import LlmFactsExtractor
from agents.algorithm_coach.factory import build_algorithm_coach
from agents.mcp_games.agent import McpGamesAgent
from agents.game_digest.agent import GameDigestAgent, GameFeedGateway, GameCatalogGateway
from agents.game_reports.agent import GameReportsAgent
from agents.game_orchestrator.agent import GameOrchestratorAgent
from infrastructure.tool_completions import ChatCompletionsToolClient, DemoToolClient, ToolProviderRouter


def build_registry(
    settings: Settings,
    http: httpx.AsyncClient,
    store: ConversationStore,
    catalog: ModelCatalog | None = None,
    usage_repository=None,
    summary_repository=None,
    facts_repository=None,
    branch_service=None,
    memory_repository=None,
    workflow_repository=None,
    invariant_repository=None,
    mcp_gateway=None,
    mcp_events=None,
    mcp_artifacts=None,
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
    memory = ContextMemory(
        summary_repository,
        LlmSummarizer(llm, accounting, catalog),
        facts_repository,
        LlmFactsExtractor(llm, accounting, catalog) if facts_repository else None,
    ) if summary_repository else None
    agents = [build_dialogue_agent(
        config, llm, store, catalog=catalog, accounting=accounting,
        memory=memory, branch_service=branch_service,
    )]
    if memory_repository is not None:
        agents.append(build_algorithm_coach(settings.model, llm, store, memory_repository, catalog, accounting,
                                            workflow_repository, invariant_repository))
    if mcp_gateway is not None and mcp_events is not None and catalog is not None:
        tool_llm = ToolProviderRouter(catalog, {
            "deepseek": DemoToolClient() if settings.mode == "demo" else ChatCompletionsToolClient(
                http, settings.api_key.get_secret_value(), "deepseek", settings.base_url),
            "mistral": DemoToolClient() if settings.mode == "demo" else ChatCompletionsToolClient(
                http, settings.mistral_api_key.get_secret_value(), "mistral", settings.mistral_base_url),
        })
        agents.append(McpGamesAgent(tool_llm, store, catalog, accounting, mcp_gateway, mcp_events,
                                    settings.model, allowed_server_ids={"games-mock", "java-games-mock"}))
        if settings.game_report_mcp_url:
            agents.append(GameReportsAgent(tool_llm, store, catalog, accounting,
                                           mcp_gateway, mcp_events, settings.model))
        if (settings.java_mcp_url and settings.game_report_mcp_url
                and settings.go_report_mcp_url and mcp_artifacts is not None):
            agents.append(GameOrchestratorAgent(tool_llm, store, catalog, accounting,
                mcp_gateway, mcp_events, mcp_artifacts, settings.model))
    if settings.game_feed_mcp_url and settings.java_mcp_url:
        agents.append(GameDigestAgent(store, GameFeedGateway(settings.game_feed_mcp_url),
                                      GameCatalogGateway(settings.java_mcp_url)))
    return AgentRegistry(agents)

