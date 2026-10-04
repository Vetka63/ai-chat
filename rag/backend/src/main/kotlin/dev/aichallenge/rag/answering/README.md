# Генерация ответов дня 22

Домен `answering` добавляет ответы DeepSeek к готовому поиску дня 21. Он не меняет индексы и не является хранилищем истории чата.

`AnswerController` принимает POST `/api/v1/answers`, выдаёт безопасные настройки через GET `/api/v1/answer-settings` и контрольные вопросы через GET `/api/v1/evaluation/questions`. Параметры проверяются Bean Validation; ключ не принимается из HTTP-запроса клиента.

`AnswerService` выбирает режим. BASELINE сразу собирает запрос из вопроса и системной инструкции, не вызывая repository/SearchService/Ollama. RAG проверяет индекс, вызывает SearchService и отбирает целые чанки по бюджету. В дне 23 финальная генерация выделена в `AnswerGenerator`: он принимает исходный вопрос и готовый AnswerContext, собирает messages, вызывает LlmClient и возвращает текст/usage/cost. Его использует и старый AnswerService, и новый ExperimentService; retrieval и rewrite он не выполняет.

`PromptAssembler` сохраняет одну системную инструкцию для обоих режимов. Книга передаётся как JSON-данные в пользовательском сообщении. Это изоляция ролей и инструкция модели, не математическая гарантия защиты от prompt injection. Пустой RAG-контекст из-за маленького бюджета отклоняется до платного вызова.

`LlmClient` — порт генератора. `DeepSeekLlmClient` — адаптер HTTP chat/completions: thinking disabled, temperature=0, отсутствие max_tokens при null, таймаут и один вызов без retry. Финальный текст и usage проверяются; reasoning_content не показывается. HTTP ошибки не включают raw body провайдера. Логи содержат статус, модель, время и числа токенов, но не ключи или тексты сообщений.

`CostEstimator` показывает диапазон USD по проверенному снимку peak/off-peak тарифов, учитывая cache hit/miss из API. При неизвестной модели/usage оценка отсутствует. Это не проверка баланса и не реальное списание.

`LlmClient.completeJson` добавлен для технического rewrite. DeepSeek adapter включает response_format=json_object только в этом методе. Обычная генерация ответов дня 22 и эксперимента дня 23 остаётся текстовой, без навязанной JSON-схемы.

`AnswerResult` содержит точные публичные messages, переданные чанки, исключённые chunk IDs, метрики, usage и finish_reason. При length частичный текст возвращается с явным флагом. Источники в DTO — материалы запроса, не автоматически подтверждённые цитаты ответа.

Контрольные вопросы хранятся в `rag/evaluation/day22-cases.json`, включаются в resources, но не индексируются. `ControlQuestionsTest` проверяет источники и опорные цитаты на pinned snapshot. Подробный алгоритм и сценарий проверки: `rag/docs/day22-guide.md`.
