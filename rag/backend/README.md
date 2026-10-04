# Kotlin backend

JDK 21, Kotlin, Spring Boot MVC. Boot создаёт компоненты через constructor injection. Доменные сервисы не вызывают конкретный SQLite/Ollama класс напрямую: зависимости идут через `DocumentLoader`, `ChunkingStrategy`, `EmbeddingProvider`, `IndexRepository`.

| Пакет | Ответственность |
|---|---|
| `documents` | Manifest, проверка SHA, нормализация, неизменяемый corpus snapshot |
| `indexing` | Две стратегии, метрики, jobs, atomic publish индекса |
| `embeddings` | Локальный HTTP embedding runtime и проверка векторов |
| `retrieval` | Вектор вопроса и exact cosine ranking |
| `answering` | Два режима генерации, сборка контекста, DeepSeek и измерения ответа |
| `config` | Пути, runtime URL, модель, batch size, timeout |
| `common` | Общий формат ошибки и hashing |

Внутри каждого домена отдельно `models`, `ports`, `adapters`, `services`, `controllers`, если они нужны. Пустые пакеты-заготовки будущих дней не создаются. `enums` находятся внутри своего домена, а не в глобальной папке всех перечислений.

Тесты находятся в `src/test/kotlin/dev/aichallenge/rag`. `gradlew.bat test bootJar` — обычная проверка. Для текущего Windows-окружения проверка выполнена через контейнер Gradle: см. `../docs/windows-setup.md`.

API contract: `../api/openapi.json`, копируется при сборке в `static/api-spec`. Изменение DTO требует синхронного обновления OpenAPI, генерации frontend типов и contract/UI checks. API не принимает произвольный путь/URL документа, размерность вектора или секрет от клиента.

Алгоритм индексации: `../docs/application-guide.md`. Генерация дня 22 по классам: `src/main/kotlin/dev/aichallenge/rag/answering/README.md` и `../docs/day22-guide.md`. Пределы и будущие домены: `../docs/architecture.md`.
