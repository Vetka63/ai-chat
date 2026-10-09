# Архитектура дня 28

Доменная карта и алгоритм по классам: [README RAG](../backend/src/main/kotlin/dev/aichallenge/local/rag/README.md). Сценарий UI: [видео-гайд](day28-video-guide.md).

## Границы

- `local-llm/client`: Vue, новый `features/rag` рядом с неизменёнными chat/lab. Клиент выбирает режим/модели для каждого вопроса; UI не знает ключ.
- `local-llm/backend`: Kotlin/Spring Boot, новый домен `rag`; существующий `LocalLlmRuntime` переиспользуется через adapter, не копируется.
- Ollama: один локальный runtime с двумя разными моделями — embedding и completion. Индекс не строится генеративной Qwen3 8B.
- SQLite: новый `rag.sqlite` в прежнем Docker volume. Чаты в `lab.sqlite` не смешиваются с документами и экспериментами.
- DeepSeek: только опциональная генерация в DEEPSEEK/COMPARE. Нет скрытых облачных проверок, переписывания вопроса и fallback.

## Взаимодействие

UI принимает вопрос → ExperimentService сохраняет UUID → RetrievalService локально вычисляет embedding и читает новый индекс → BM25+RRF выбирает кандидатов → threshold/budget формируют один snapshot → два RagGenerator запускаются параллельно → каждая карточка сохраняется отдельно → UI polling получает готовые карточки → ручной вывод и MD.

Корпус импортируется отдельно: UTF-8 → ручное исключение предисловия → точные chunk ranges → локальные вектора → атомарный READY. Сохранённый digest не позволяет тихо подменить embedding space.

## Надёжность

Ollama держит одну модель одновременно и один inference slot. Это сделано после наблюдаемого CUDA allocation failure при загрузке Qwen рядом с embedding runtime. Новый query cache (space + точный input, до 1000 векторов) позволяет повторять вопрос без повторной загрузки embedding-модели. Ответы не кешируются. UI показывает cache hit, время загрузки и неопределённые метрики отдельно.

У pinned Ollama 0.35.1 llama-server дополнительно держал prompt snapshots в RAM с лимитом 8192 MiB; в логах наблюдался рост на сотни MiB на новый контекст. `LLAMA_ARG_CACHE_RAM=0` отключает именно этот ускоряющий runtime cache, не историю чатов в SQLite и не передачу messages. Это supported env [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/master/common/arg.cpp); Ollama 0.35.1 [передаёт environment subprocess](https://github.com/ollama/ollama/blob/v0.35.1/llm/llama_server.go). Flash Attention включён явно. Причинная связь каждого общего перезапуска Docker с RAM не доказана; корректность настройки проверяется по реальным логам и итоговому прогону, не предположению.

Один RAG-эксперимент одновременно и общий GenerationGate с чатами/индексацией. COMPARE не держит HTTP запрос открытым всё время генерации: POST возвращает 202, GET выдаёт снимок. Параллельность — реальные независимые worker tasks, а не два последовательных LLM вызова. Отказ/обрезание второго ответа не удаляет первый.

Startup recovery: BUILDING/PREPARING/RUNNING становятся INTERRUPTED, завершённые данные не повторяются. Backend рассчитан на одну реплику; общий volume нельзя одновременно использовать двумя JVM с recovery. Для production потребуются пользователи, квоты, durable queue и distributed lease.

## Архитектурный рисунок

Готовая схема: [day28-rag-overview.html](diagrams/day28-rag-overview.html).
Упрощённая карта пяти реальных компонентов прошла 9/9 showcase без ошибок/предупреждений,
автоматический browser evidence и визуальное ревью light/dark. Спецификация заморожена,
SHA256/размеры и четыре desktop viewport записаны в [receipt](diagrams/day28-diagram-receipt.md).
Подписи русские, служебный Viewer UI остаётся английским.

`day28-rag.architecture.json` — только прежний непринятый кандидат; он не используется как доставленный результат.

## Завершение и воспроизведение

`scripts/start-day28.ps1`: disk guard, health checks, локальная/опциональная cloud/offline конфигурация,
без удаления данных и скрытых генераций. Offline стартует и из остановленного состояния после загрузки весов.
`scripts/verify-day28.ps1`: настраиваемый output cap только при явном выборе, выбранная локальная модель,
проверка совпадения сохранённой квитанции/настроек. В новой серии 30 сравнений обе карточки завершились;
один локальный ответ содержит фактическое противоречие. Индекс и источники не гарантируют истинность.
Итоговые доказательства/ограничения: [day28-verification.md](day28-verification.md).
