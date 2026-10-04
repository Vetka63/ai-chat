# Установка и запуск на Windows

Основной проверяемый способ запуска — Docker Desktop. Kotlin, Gradle, Node и Ollama работают в отдельных контейнерах; менять системный PATH или ставить Ollama на Windows не нужно. Старые сервисы этого репозитория не затрагиваются.

## 1. Подготовить Docker

Установите [Docker Desktop для Windows](https://docs.docker.com/desktop/setup/install/windows-install/) с WSL 2 backend. Следуйте требованиям установщика; если потребуется, включите WSL и перезагрузите Windows. Дождитесь статуса запущенного Engine.

Проверка в PowerShell:

```powershell
docker version
docker compose version
```

Для GPU нужен поддерживаемый NVIDIA-драйвер и доступ GPU из Docker/WSL. Основной Compose работает и на CPU. Установка GPU-драйвера не выполняется приложением автоматически.

## 2. Подготовить корпус

```powershell
Set-Location 'F:\ai-advent-challenge\ai-chat\rag'
.\scripts\prepare-corpus.ps1
```

36 файлов выбранных глав и вспомогательный Ruby-пример уже находятся в `corpus/progit-ru`. Если актуальный manifest есть, скрипт проверяет хеш каждого файла и не скачивает/перезаписывает его. Старый manifest без resources дополняется только закреплённым Ruby-примером того же revision. В чистом checkout без корпуса скрипт загружает закреплённый commit из GitHub, выбирает разрешённые `.asc` и ресурс, сохраняет лицензию и manifest. Не запускает команды из книги. При ошибке целостности остановитесь и выясните причину; не обходите проверку.

## 3. Запустить отдельное приложение

С NVIDIA GPU:

```powershell
docker compose -f compose.yml -f compose.gpu.yml up -d --build
docker compose exec ollama ollama pull qwen3-embedding:0.6b
```

Без GPU:

```powershell
docker compose up -d --build
docker compose exec ollama ollama pull qwen3-embedding:0.6b
```

Первая загрузка Docker-образов и модели требует интернета, свободного места и времени. Последующие индексирование и поиск выполняются локально. DeepSeek API key для дня 21 не нужен. Загрузка модели — отдельный шаг: запуск контейнера не означает, что модель уже загружена.

Открыть http://localhost:8383. Проверить API:

```powershell
Invoke-RestMethod http://localhost:8382/actuator/health
Invoke-RestMethod http://localhost:8382/api/v1/corpus
docker compose ps
docker compose exec ollama ollama list
```

После первого embedding-запроса команда `docker compose exec ollama ollama ps` показывает, где выполняется модель. CPU — допустимый fallback, не ошибка алгоритма.

## 4. Данные и перезапуск

Compose project: `ai-chat-rag-day-21`. Volumes:

- `rag-data`: `/app/data/rag.sqlite`, индексы, исходные snapshots и история jobs;
- `ollama-models`: скачанные embedding-модели.

Перезапуск только нового backend:

```powershell
docker compose restart backend
```

Пересборка после изменения кода:

```powershell
docker compose -f compose.yml -f compose.gpu.yml up -d --build
```

Остановить только RAG без удаления данных:

```powershell
docker compose stop
```

Не используйте `down -v`, если хотите сохранить индексы и модель. Незавершённый job после restart становится FAILED с объяснением; готовые индексы остаются.

## 5. Настройки

`.env.example` не содержит секретов. Необязательный `.env` позволяет переопределить `RAG_UI_PORT`, `RAG_API_PORT`, `RAG_OLLAMA_PORT`, `EMBEDDING_MODEL`, `EMBEDDING_BATCH_SIZE`, `EMBED_TIMEOUT_SECONDS`.

По умолчанию UI 8383, API 8382, Ollama 11435; все порты привязаны к localhost. Backend внутри Docker обращается к `http://ollama:11434`, а не к опубликованному Windows-порту. Nginx направляет `/api` и `/actuator` в backend. Модель/digest/dimension входят в идентичность индекса: замена модели требует нового индекса, смешивать вектора нельзя.

## 6. Работа с исходниками без Docker

Нужны JDK 21, Node >=22.12 и доступ к Ollama HTTP. Отдельный глобальный Kotlin/Gradle не нужен: есть Gradle Wrapper с checksum дистрибутива. Для frontend рекомендуется Node 24; `npm.cmd` позволяет избежать конфликтующего PowerShell shim.

```powershell
Set-Location 'F:\ai-advent-challenge\ai-chat\rag\backend'
$env:SERVER_PORT = '8382'
.\gradlew.bat test bootRun
```

В другом окне:

```powershell
Set-Location 'F:\ai-advent-challenge\ai-chat\rag\client'
npm.cmd ci
npm.cmd run build
npm.cmd test
npm.cmd run dev
```

Vite proxy настроен на backend localhost:8382, поэтому выше явно задаём SERVER_PORT. Остановите Docker client/backend перед нативным запуском, чтобы освободить эти порты; Ollama можно оставить в контейнере на localhost:11435. SQLite вне Docker создаётся в `rag/data`, отдельно от Docker volume.

На проверяемой Windows-машине системный Gradle daemon не смог создать loopback-соединение, даже с IPv4. Поэтому результаты сборки получены в Linux-контейнере, а не выдаются за успешный нативный запуск. Docker — рабочий обход без изменения firewall и системных установок.

## Диагностика

```powershell
docker compose logs --tail 100 backend
docker compose logs --tail 60 ollama
```

- `model_missing`: выполните `ollama pull` внутри правильного Compose project.
- `ollama_unavailable`: проверьте контейнер и timeout. При CPU уменьшите batch size или увеличьте timeout.
- `embedding_model_mismatch`/`embedding_changed`: не меняйте модель посреди построения; запустите новое задание.
- `indexing_busy`: дождитесь текущего задания; на экземпляре backend разрешён один builder.
- HTTP 400: исправьте размер чанка/overlap/пустой поисковый запрос.
- Ошибки загрузки внешних образов: это сеть/registry, а не ошибка chunking.

Не публикуйте локальные порты в интернет: аутентификация, TLS и пользовательские квоты в учебный день 21 не входят.

## Повторить автоматические проверки

Из `rag` можно запустить `pwsh -File ./scripts/verify-live.ps1 -BuildIndexes`. Скрипт требует **PowerShell 7**: если готовые 3000/300 индексы есть, использует их; иначе строит оба. Выполняет пять поисковых случаев, проверку метаданных/snapshot substring и негативные HTTP cases. Отчёт — `data/live-verification.json`, он исключён из Git. Без флага -BuildIndexes новые индексы не создаёт.

Браузерные тесты используют установленный Chrome (`npm run test:e2e` в client). `RAG_BROWSER_CHANNEL` позволяет выбрать другой поддерживаемый Playwright channel; `RAG_UI_URL` — адрес живого UI. Проверки сравнения индексов требуют двух готовых индексов. Обычные тесты не обращаются к платной генерации; отдельный тест ответов с RAG_LIVE=true делает 2 вызова DeepSeek.

## Запуск генерации дня 22

Индексация дня 21 не требует ключа. Для ответов дня 22 используйте существующий корневой `.env` с LLM_API_KEY либо задайте DEEPSEEK_API_KEY в игнорируемом локальном `.env`. Из `rag` запускайте `docker compose --env-file ../.env -f compose.yml -f compose.gpu.yml up -d --build`. Ключ попадёт только в backend environment, не в frontend. Подробный алгоритм, настройки и сценарий UI — [day22-guide.md](day22-guide.md). Не выводите compose config или env контейнера при записи видео.
