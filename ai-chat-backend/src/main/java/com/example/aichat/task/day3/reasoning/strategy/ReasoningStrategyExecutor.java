package com.example.aichat.task.day3.reasoning.strategy;

import com.example.aichat.task.day3.reasoning.enums.ReasoningStrategy;
import com.example.aichat.task.day3.reasoning.model.ReasoningExperimentContext;
import com.example.aichat.task.day3.reasoning.model.ReasoningStrategyResult;

/** Задаёт общий контракт подключаемой стратегии рассуждения Дня 3. */
public interface ReasoningStrategyExecutor {
    ReasoningStrategy strategy();

    ReasoningStrategyResult execute(ReasoningExperimentContext context);
}
