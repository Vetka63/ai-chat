# Лаборатория локальных LLM

Отдельное приложение для новой недели AI Advent Challenge. Backend — Kotlin / Spring Boot, клиент — Vue 3 / TypeScript. День 26 запускает локальную модель; день 27 — чат с историей; день 28 — новый локальный RAG по русским рассказам Warhammer с опциональным параллельным сравнением с DeepSeek.

День 28: [UI/видео-гайд](docs/day28-video-guide.md), [архитектура](docs/day28-architecture.md), [классы и алгоритм](backend/src/main/kotlin/dev/aichallenge/local/rag/README.md). Новый SQLite-индекс не меняет прежний Pro Git. LOCAL не вызывает облако; DEEPSEEK/COMPARE явно отправляют вопрос и найденные фрагменты провайдеру. Полные книги/индексы/ответы в Git не включены.

## Запуск на Windows

Нужны Docker Desktop с Linux containers / WSL2 и драйвер NVIDIA для GPU. На проверенном компьютере: RTX 4080 SUPER, 16 ГБ VRAM, около 32 ГБ RAM. Для CPU можно не подключать `compose.gpu.yml`.

В PowerShell:

```powershell
cd F:\ai-advent-challenge\ai-chat\local-llm
docker compose -f compose.yml -f compose.gpu.yml up -d ollama
docker compose -f compose.yml -f compose.gpu.yml exec ollama ollama pull qwen3:8b
docker compose -f compose.yml -f compose.gpu.yml exec ollama ollama pull qwen3-embedding:0.6b
docker compose -f compose.yml -f compose.gpu.yml up -d --build backend client
```

Скачивание `qwen3:8b` занимает около 5,2 ГБ, embedding-модель — около 639 МБ; для загрузки нужны сеть и время. После загрузки дни 26–27 и LOCAL дня 28 работают локально без API-ключа. Cloud-функции Ollama отключены `OLLAMA_NO_CLOUD=1`; отдельный DeepSeek adapter включается только для сравнения. Этот экземпляр Ollama и его volume независимы от прежнего Pro Git. В RAG дня 28 модели загружаются по одной (`OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_NUM_PARALLEL=1`): это снижает пиковую нагрузку, но смена embedding/completion может добавлять время загрузки.

- UI: http://localhost:8483
- Backend: http://localhost:8482/api/v1
- Ollama: http://localhost:11436

В UI три раздела: «День 28 · RAG», «День 27 · Чат» и «День 26 · Проверки». Лабораторные запуски независимы, а сообщения чата передаются в Ollama вместе с предыдущими репликами выбранного диалога и его системной инструкцией. Таблицы `runs`, `chats`, `messages` находятся в `lab.sqlite`; новый корпус/индекс/эксперименты — отдельно в `rag.sqlite`. Все сохранённые результаты доступны из разных браузеров.

Перезапуск сохраняет завершённые результаты; незавершённые помечаются ошибкой без автоматической генерации. В чате неудачное сообщение пользователя остаётся видимым. Последний неуспешный ход можно повторить явно, без дублирования реплики. Удаление чата требует подтверждения и удаляет только его сообщения. Приложение рассчитано на одного локального пользователя, без аккаунтов; не публикуйте его API в интернет.

## Управление

```powershell
# Перезапустить только приложение этой недели
docker compose -f compose.yml -f compose.gpu.yml restart backend client
# Остановить только это приложение, сохранив модели и данные
docker compose -f compose.yml -f compose.gpu.yml stop
# Проверить фактическую загрузку модели на GPU
docker compose -f compose.yml -f compose.gpu.yml exec ollama ollama ps
```

Модели меняются серверной настройкой `LOCAL_LLM_MODELS` (список через запятую) и предварительно загружаются командой `ollama pull`. Список в UI формируется по реально установленным разрешённым моделям. Cloud-модели и embedding-модели не выполняются через генерационный API.

Пустой лимит выхода не добавляет `num_predict` в запрос: используется настройка Ollama. Контекст по умолчанию — 8192 токена, температура — 0,7, reasoning выключен. При заданном лимите токены reasoning тоже входят в ограничение. Обрезание отмечается `TRUNCATED`; отсутствие ответа — `FAILED`. Размер prompt ограничен 16000 символами для первой лаборатории; это проверка размера HTTP-входа, не оценка токенов.

Чат передаёт полную историю завершённых ходов без сжатия. `truncate=false` запрещает Ollama молча выбрасывать начало переписки при превышении окна, `shift=false` запрещает сдвиг контекста во время генерации. Переполнение может дать явную ошибку или незавершённый ответ; оно не удаляет сохранённую историю. Модель фиксируется при создании чата, остальные параметры можно менять перед каждым сообщением. Сведения из других чатов не передаются.

## Документация

- [Устройство приложения](docs/architecture.md)
- [Проверка и запись видео дня 26](docs/day26-video-guide.md)
- [Проверка и запись видео дня 27](docs/day27-video-guide.md)
- [Алгоритм чата по классам](backend/src/main/kotlin/dev/aichallenge/local/chat/README.md)

Официальные источники: [Ollama chat API](https://docs.ollama.com/api/chat), [qwen3:8b](https://ollama.com/library/qwen3:8b), [GPU в Docker Desktop](https://docs.docker.com/desktop/features/gpu/).
