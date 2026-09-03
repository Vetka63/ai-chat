package com.example.aichat.common.inputpolicy;

import com.example.aichat.common.inputpolicy.model.InputHistoryMessage;
import com.example.aichat.common.llm.model.LlmMessage;
import com.example.aichat.common.profile.model.AgentProfile;
import com.example.aichat.common.profile.model.ResponseModeConfig;
import java.util.List;

/**
 * Преобразует запрос пользователя и необязательную историю в независимые от провайдера сообщения LLM.
 * Конкретная реализация выбирается через {@link InputPolicyRegistry} согласно настройкам профиля.
 */
public interface InputPolicy {

    /** Возвращает стабильный идентификатор политики, используемый в конфигурации. */
    String type();

    /** Формирует и проверяет сообщения перед отправкой провайдеру LLM. */
    List<LlmMessage> buildMessages(
            AgentProfile profile,
            ResponseModeConfig responseMode,
            String rawMessage,
            List<InputHistoryMessage> history
    );
}
