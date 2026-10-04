# Проверка актуальной схемы RAG

Версия v2 отражает Kotlin/Spring Boot backend, Vue 3/TypeScript frontend и QASPER snapshot. Это предлагаемая архитектура, не развернутый сервис. Авторские подписи русские; встроенные кнопки viewer и HTML language используют английский fallback Archify.

```text
diagram_type: architecture
output: F:\ai-advent-challenge\ai-chat\rag\docs\diagrams\rag-week-architecture-v2.html
specification_sha256: 9a8fcd2e3008bbe36f3a4f44f1d2fc1c30f938b338292f963ee51b4016c5e50d
specification_bytes: 1938
artifact_sha256: 5e5195646a8fd478c1a86450fe2a227d4f333f2f2c522d5f989a2561a3f5e31d
artifact_bytes: 804086
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 1
```

Одна точечная коррекция сместила подпись JDBC, перекрывавшую backend. После неё validate и deliver завершились успешно. Browser evidence проверяет 1440×900, 1600×1000, 1920×1080 и 2048×1320. Светлый 1440×900 и тёмный 2048×1320 screenshots просмотрены визуально: компоненты/подписи читаются, маршруты не пересекают посторонние узлы, обрезания нет.

Автоматический receipt всегда оставляет visualReview pending: perceptual review записан здесь отдельно. Артефакт сохраняется в репозиторий без изменения байтов; SHA256 связывает review с конечным HTML. Первая схема без суффикса v2 — историческое предложение до пользовательской корректировки.
