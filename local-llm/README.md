# Лаборатория локальных LLM

Отдельное приложение для новой недели AI Advent Challenge. Backend — Kotlin / Spring Boot, клиент — Vue 3 / TypeScript. День 26 запускает локальную модель и даёт три независимых запроса. День 27 добавляет обычный чат с серверной историей и передачей контекста локальной модели.

## Запуск на Windows

### День 30: приватный доступ с iPhone через ZeroTier

В ветке `ai-challenge-day-30` используйте **отдельный** `compose.day30.yml`, не команды дней 26/27 ниже. Чат: `http://127.0.0.1:8583/`, API диагностики: `127.0.0.1:8582`, новая SQLite `day30.sqlite`. Ollama без опубликованного порта и cloud fallback. Старый volume весов переиспользуется, старая история не удаляется.

```powershell
cd F:\ai-advent-challenge\ai-chat
.\local-llm\scripts\Initialize-Day30.ps1
.\local-llm\scripts\Start-Day30.ps1 -Build
```

Пароль генерируется только локально в `.env.day30` и не попадает в Git. Сначала проверяйте чат на ПК, затем подключайте iPhone по [пошаговому гайду](docs/day30-zerotier-windows-ios.md). Требуется один административный запуск сетевого скрипта; весь Firewall не отключается.

Описание: [архитектура и ограничения](docs/day30-architecture.md), [схема Archify](docs/diagrams/day30-private-service.html), [проверка и видео](docs/day30-video-guide.md), [результаты проверки](docs/day30-verification.md).

### Дни 26/27: локальный доступ на одном ПК

Нужны Docker Desktop с Linux containers / WSL2 и драйвер NVIDIA для GPU. На проверенном компьютере: RTX 4080 SUPER, 16 ГБ VRAM, около 32 ГБ RAM. Для CPU можно не подключать `compose.gpu.yml`.

В PowerShell:

```powershell
cd F:\ai-advent-challenge\ai-chat\local-llm
docker compose -f compose.yml -f compose.gpu.yml up -d ollama
docker compose -f compose.yml -f compose.gpu.yml exec ollama ollama pull qwen3:8b
docker compose -f compose.yml -f compose.gpu.yml up -d --build backend client
```

Скачивание `qwen3:8b` занимает около 5,2 ГБ на диске; для загрузки нужны сеть и время. После загрузки запросы выполняются локально. Cloud-функции Ollama отключены `OLLAMA_NO_CLOUD=1`; API-ключи не нужны. Это отдельный экземпляр Ollama с собственным volume, он не использует embedding-модель RAG.

- UI: http://localhost:8483
- Backend: http://localhost:8482/api/v1
- Ollama: http://localhost:11436

В UI два раздела: «День 27 · Чат» и «День 26 · Проверки». Лабораторные запуски независимы, а сообщения чата передаются в Ollama вместе с предыдущими репликами выбранного диалога и его системной инструкцией. Таблицы `runs`, `chats`, `messages` находятся в одной серверной SQLite, но их данные не смешиваются. Все сохранённые результаты доступны из разных браузеров.

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

Пустой лимит выхода не добавляет `num_predict` в запрос: используется настройка Ollama. Контекст по умолчанию — 8192 токена, температура — 0,7, reasoning выключен. При заданном лимите токены reasoning тоже входят в ограничение. Обрезание отмечается `TRUNCATED`, включая пустой финальный ответ при исчерпании лимита: thinking и метрики сохраняются. Пустой ответ с нормальным завершением — `FAILED`. Размер prompt ограничен 16000 символами для первой лаборатории; это проверка размера HTTP-входа, не оценка токенов.

Чат передаёт полную историю завершённых ходов без сжатия. `truncate=false` запрещает Ollama молча выбрасывать начало переписки при превышении окна, `shift=false` запрещает сдвиг контекста во время генерации. Переполнение может дать явную ошибку или незавершённый ответ; оно не удаляет сохранённую историю. Модель фиксируется при создании чата, остальные параметры можно менять перед каждым сообщением. Сведения из других чатов не передаются.

## Документация

- [Устройство приложения](docs/architecture.md)
- [Проверка и запись видео дня 26](docs/day26-video-guide.md)
- [Проверка и запись видео дня 27](docs/day27-video-guide.md)
- [Алгоритм чата по классам](backend/src/main/kotlin/dev/aichallenge/local/chat/README.md)

Официальные источники: [Ollama chat API](https://docs.ollama.com/api/chat), [qwen3:8b](https://ollama.com/library/qwen3:8b), [GPU в Docker Desktop](https://docs.docker.com/desktop/features/gpu/).
