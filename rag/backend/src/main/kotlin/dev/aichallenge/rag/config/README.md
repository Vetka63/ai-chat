# config

`RagProperties` — server-owned настройки путей и embedding runtime: corpusPath, databasePath, ollamaUrl, embeddingModel, batchSize, embedTimeoutSeconds. Defaults задаются в `application.yml`, Docker overrides — в Compose.

Пользователь через UI меняет только параметры эксперимента chunking и query. Он не выбирает произвольный filesystem path, URL для серверного запроса, API key или размерность вектора. День 21 не требует секретов.

Для замены runtime адаптера используйте `EmbeddingProvider`; не добавляйте provider-specific HTTP в controller или chunking service.
