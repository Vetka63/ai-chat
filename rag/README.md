# Лаборатория RAG дня 21

Отдельный Kotlin backend и Vue client индексируют русский перевод Pro Git. В день 21 приложение строит два локальных индекса, показывает исходники и чанки, сравнивает стратегии и выполняет диагностический поиск. Генеративного чата, DeepSeek-вызовов и памяти диалога пока нет.

Старые Java/Python приложения остаются рядом и не изменяются. Ветка реализации — `ai-challenge-day-21`. Дни 22–25 будут отдельными последовательными ветками после проверки предыдущего дня; объединение в main и push выполняются по отдельной инструкции.

## Быстрый запуск

Из PowerShell в каталоге `F:\ai-advent-challenge\ai-chat\rag`:

```powershell
.\scripts\prepare-corpus.ps1
docker compose -f compose.yml -f compose.gpu.yml up -d --build
docker compose exec ollama ollama pull qwen3-embedding:0.6b
```

Если GPU недоступен, используйте только `docker compose up -d --build`: CPU поддерживается, но расчёт будет медленнее. Исходники выбранных глав и manifest уже подготовлены в репозитории; первый шаг проверяет их хеши и ничего не перезаписывает.

- UI: http://localhost:8383
- API: http://localhost:8382/api/v1/corpus
- Health: http://localhost:8382/actuator/health
- OpenAPI: http://localhost:8382/api-spec/openapi.json
- Локальный Ollama: http://localhost:11435

Порты опубликованы только на `127.0.0.1`. Compose использует отдельный project `ai-chat-rag-day-21`, volumes для SQLite и модели. Команды этой папки не останавливают прежние приложения.

## Документация

- [Архитектура и границы доменов](docs/architecture.md)
- [Алгоритм работы по классам](docs/application-guide.md)
- [Установка и запуск Windows](docs/windows-setup.md)
- [Проверка задания через UI](docs/day21-testing.md)
- [Результаты тестов и фактическое сравнение стратегий](docs/test-results.md)
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
