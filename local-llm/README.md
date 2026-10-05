# Лаборатория локальных LLM

Отдельное приложение для новой недели AI Advent Challenge. Backend — Kotlin / Spring Boot, клиент — Vue 3 / TypeScript. День 26 запускает локальную генеративную модель через Ollama и даёт три запроса разной сложности.

## Запуск на Windows

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

UI сохраняет запуски в серверной SQLite, включая ошибки. Данные доступны из разных браузеров. Каждый запрос независим: предыдущие запуски не образуют историю LLM-контекста. Перезапуск сохраняет завершённые результаты; незавершённые помечаются ошибкой без автоматической повторной генерации.

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

## Документация

- [Устройство приложения](docs/architecture.md)
- [Проверка и запись видео дня 26](docs/day26-video-guide.md)

Официальные источники: [Ollama chat API](https://docs.ollama.com/api/chat), [qwen3:8b](https://ollama.com/library/qwen3:8b), [GPU в Docker Desktop](https://docs.docker.com/desktop/features/gpu/).
