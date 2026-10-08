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

`diagrams/day28-rag.architecture.json` — **непринятый кандидат**, не подтверждённая HTML-диаграмма. Геометрические проверки соединений прошли, но showcase checker выявил недостаточную читаемость node context при desktop 1440×900. После двух правок без уменьшения количества ошибок работа над кандидатом остановлена согласно archify. HTML не доставлен, browser evidence и visual review для него не выполнялись. Для текущей реализации используйте текстовое описание выше, не выдавайте JSON за готовую схему.
