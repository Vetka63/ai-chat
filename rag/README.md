# Лаборатория RAG дней 21–25

Отдельный Kotlin backend и Vue client индексируют русский перевод Pro Git. День 21 — два локальных индекса, исходники, чанки и диагностический поиск. День 22 — ответы DeepSeek с RAG и без RAG. День 23 — фильтр, query rewrite и сравнение режимов. День 24 — grounded-флоу: пункты с точными цитатами, fail-closed проверка и «не знаю» при слабом контексте. День 25 — сохраняемые чаты с отдельной памятью задачи, contextual query, новым поиском каждого вопроса и источниками.

Старые Java/Python приложения остаются рядом и не изменяются. Текущая ветка — `ai-challenge-day-25`, от сохранённого коммита дня 24 `d6f91a0`. Объединение в main и push выполняются по отдельной инструкции.

## Быстрый запуск

Из PowerShell в каталоге `F:\ai-advent-challenge\ai-chat\rag`:

```powershell
.\scripts\prepare-corpus.ps1
docker compose --env-file ../.env -f compose.yml -f compose.gpu.yml up -d --build
docker compose exec ollama ollama pull qwen3-embedding:0.6b
```

Команда выше использует существующий корневой .env с LLM_API_KEY или DEEPSEEK_API_KEY. При отдельном `rag/.env` не указывайте `--env-file ../.env`. Ключ передаётся только backend, не browser/build. Если GPU недоступен, уберите `-f compose.gpu.yml`: CPU работает медленнее. Подробности — [день 22](docs/day22-guide.md). Скрипт корпуса проверяет хеши и не перезаписывает исходники.

- UI: http://localhost:8383
- API: http://localhost:8382/api/v1/corpus
- Health: http://localhost:8382/actuator/health
- OpenAPI: http://localhost:8382/api-spec/openapi.json
- Локальный Ollama: http://localhost:11435

Порты опубликованы только на `127.0.0.1`. Compose использует отдельный project `ai-chat-rag-day-21`, volumes для SQLite и модели. Команды этой папки не останавливают прежние приложения.

## Документация

- [План и сверка дня 25](docs/day25-plan-review.md)
- [Понятный алгоритм и шаги проверки чата](docs/day25-guide.md)
- [Фактические проверки чата, памяти и restart](docs/day25-test-results.md)
- [Схема дня 25](docs/diagrams/day25-chat.html) и [receipt](docs/diagrams/day25-acceptance.md)
- [Два длинных сценария](evaluation/day25-scenarios.json)
- [Сверка требований дня 24](docs/day24-plan-review.md)
- [Алгоритм и самостоятельная проверка дня 24](docs/day24-guide.md)
- [Результаты десяти ответов с цитатами](docs/day24-test-results.md)
- [Схема дня 24](docs/diagrams/day24-grounding.html) и [подтверждение проверки](docs/diagrams/day24-acceptance.md)
- [Саморевью плана дня 23](docs/day23-plan-review.md)
- [Алгоритм и самостоятельная проверка дня 23](docs/day23-guide.md)
- [Результаты сравнения фильтра и rewrite](docs/day23-test-results.md)
- [Схема дня 23](docs/diagrams/day23-architecture.html) и [подтверждение проверки](docs/diagrams/day23-acceptance.md)
- [Алгоритм и проверка дня 22](docs/day22-guide.md)
- [Схема дня 22](docs/diagrams/day22-architecture.html)
- [Архитектура и границы доменов](docs/architecture.md)
- [Алгоритм работы по классам](docs/application-guide.md)
- [Установка и запуск Windows](docs/windows-setup.md)
- [Проверка задания через UI](docs/day21-testing.md)
- [Результаты тестов и фактическое сравнение стратегий](docs/test-results.md)
- [Результаты дня 22 и сравнение 10 пар ответов](docs/day22-test-results.md)
- [Подтверждение проверки схемы дня 22](docs/diagrams/day22-acceptance.md)
- [Корпус Pro Git и правила обработки](docs/corpus-progit.md)
- [Сценарии оценки](docs/evaluation-scenarios.md)
- [План следующих дней](docs/implementation-plan.md)
- [Обоснование решений](docs/decisions.md)
- [Схема Archify](docs/diagrams/day21-architecture.html)
- [Подтверждение проверки схемы](docs/diagrams/day21-acceptance.md)

Описание модулей: [backend](backend/README.md), [client](client/README.md), [API-контракт](api/README.md). В backend рядом с каждым доменом также лежит свой README.

`api/openapi.json` — источник клиентских типов. `client/src/api/schema.d.ts` генерируется командой `npm run generate:api`, вручную не редактируется. В backend-доменах рядом с кодом лежат README и русские KDoc.

## Подсчёты и ограничения

Размер чанка и overlap задаются в символах UTF-16 нормализованного текста, не в токенах. Coverage считается по объединению исходных диапазонов; повторяющиеся overlap-фрагменты не увеличивают уникальный объём. Условные страницы — слова / 400, а не номера страниц PDF.

Ollama создаёт настоящие вектора; точный cosine search выполняется на Kotlin. Similarity не является вероятностью правильного ответа. Поля embedding tokens показывают usage обработанных чанков, полученный от runtime; диагностические dimension probes в это число не входят. Объём векторов не равен полному размеру SQLite.

Индексы и jobs переживают restart. Незавершённое задание после restart становится FAILED; оно не восстанавливается автоматически. Готовые индексы не теряются. Автоматическое продолжение частично вычисленных batches не входит в день 21.

## Источник книги

[Pro Git на русском](https://git-scm.com/book/ru/v2), Scott Chacon и Ben Straub, русский перевод участников проекта. [Исходники](https://github.com/progit/progit2-ru), commit `bef0ce52475e44b8c9d66d7ff8c6a03b37758a03`. Лицензия контента — CC BY-NC-SA 3.0; файл лицензии и attribution сохранены в `corpus/progit-ru`. Проект не выдаёт нормализованный текст за оригинальное издание.
