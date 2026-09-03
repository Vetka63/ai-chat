package com.example.aichat.common.llmjudge;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Хранит подключаемые LLM-судьи и проверяет совместимость их входных и выходных типов. */
@Component
public class LlmJudgeRegistry {

    private final Map<String, LlmJudge<?, ?>> judges;

    public LlmJudgeRegistry(List<LlmJudge<?, ?>> judges) {
        Map<String, LlmJudge<?, ?>> indexed = new LinkedHashMap<>();
        for (var judge : judges) {
            if (indexed.putIfAbsent(judge.type(), judge) != null) {
                throw new IllegalStateException("Duplicate LLM judge: " + judge.type());
            }
        }
        this.judges = Map.copyOf(indexed);
    }

    /** Возвращает судью, только если объявленные типы команды и результата совпадают. */
    @SuppressWarnings("unchecked")
    public <I, O> LlmJudge<I, O> get(String type, Class<I> inputType, Class<O> resultType) {
        var judge = judges.get(type);
        if (judge == null) {
            throw new IllegalStateException("Unknown LLM judge: " + type);
        }
        if (!judge.inputType().equals(inputType) || !judge.resultType().equals(resultType)) {
            throw new IllegalStateException("LLM judge type mismatch: " + type);
        }
        return (LlmJudge<I, O>) judge;
    }
}
