# Домен indexing

Владеет chunking, измерениями и жизненным циклом построения. Не занимается генерацией ответа.

- `models`: config, chunk metadata/ranges, metrics, embedding identity, index, job и comparison DTO.
- `enums`: `ChunkStrategy` и `JobStatus`.
- `ports/ChunkingStrategy`: выдаёт диапазоны текста документа; новая стратегия подключается реализацией порта.
- `services/ChunkingService`: FIXED/STRUCTURAL, проверка конфигурации, substring chunks и стабильные IDs, coverage/length/code-boundary metrics.
- `ports/IndexRepository`: сохранение jobs, чтение готовых данных и атомарная публикация.
- `adapters/SqliteIndexRepository`: JDBC SQLite/WAL, JSON metadata/snapshot, Float32 BLOB vectors. На restart незавершённые jobs переводятся в FAILED.
- `services/IndexingService`: один background executor и busy guard; embedding batches; проверка dimension/identity; publish только после полного успеха.
- `controllers/IndexController`: preview, jobs, indexes, chunks, сохранённый документ индекса и comparison.

Порядок: QUEUED → RUNNING → READY либо FAILED. Job пишется до вычисления; готовый snapshot, index, chunks/vectors и READY status фиксируются одной транзакцией. При exception rollback не оставляет видимый «готовый» индекс с частью векторов.

Один builder — локальное ограничение ресурсов текущего приложения. Это не распределённый scheduler: второй backend с тем же SQLite не поддерживается. В будущем queue/repository можно заменить, сохранив DTO/контракты.

FIXED работает внутри каждого файла. STRUCTURAL работает внутри разделов, предпочитает границы блоков, но ограничивает размер и может делить длинный code block; overlap тоже может попасть внутрь него. Метрики показывают этот компромисс.
