import random

from app.agents.registry import AgentRegistry
from app.core.settings import Settings
from app.domain.chat import ChatMeta, ChatRequest, ChatResponse
from app.llm.deepseek import LlmClient
from app.policies.input import InputPolicy
from app.policies.output import OutputPolicyRegistry


FALLBACK_REPLIES = (
    "Интересная мысль. Расскажите об этом немного подробнее.",
    "Я получил ваше сообщение — fallback-режим работает.",
    "Хороший вопрос! Подключите DeepSeek, чтобы получить содержательный ответ.",
    "Сейчас отвечает тестовый помощник. Связь с backend установлена.",
    "Принято. В боевом режиме этот запрос будет передан языковой модели.",
)


class ChatOrchestrator:
    def __init__(
        self,
        settings: Settings,
        registry: AgentRegistry,
        llm_client: LlmClient,
        input_policy: InputPolicy | None = None,
        output_policies: OutputPolicyRegistry | None = None,
    ):
        self._settings = settings
        self._registry = registry
        self._llm_client = llm_client
        self._input_policy = input_policy or InputPolicy()
        self._output_policies = output_policies or OutputPolicyRegistry()

    async def reply(self, request: ChatRequest) -> ChatResponse:
        profile_id = request.profile_id or self._settings.default_agent_id
        profile = self._registry.get(profile_id)
        messages = self._input_policy.build_messages(
            profile,
            request.message,
            request.history,
        )

        if self._settings.chat_mode == "fallback":
            return ChatResponse(
                reply=random.choice(FALLBACK_REPLIES),
                source="fallback",
                profileId=profile.id,
            )

        result = await self._llm_client.complete(profile, messages)
        output_policy = self._output_policies.get(profile.output_policy.type)
        reply = output_policy.apply(result)
        return ChatResponse(
            reply=reply,
            source="llm",
            profileId=profile.id,
            meta=ChatMeta(
                model=result.model,
                finishReason=result.finish_reason,
            ),
        )
