package com.example.aichat.common.profile.validator;

import com.example.aichat.common.profile.model.AgentProfile;

/** Задаёт контракт проверки специфичной для задачи конфигурации профиля при запуске. */
public interface AgentFeatureConfigValidator {
    String experienceType();

    void validate(AgentProfile profile);
}
