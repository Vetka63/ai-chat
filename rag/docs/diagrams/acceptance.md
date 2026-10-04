# Проверка архитектурной схемы RAG

Схема описывает предлагаемую архитектуру, а не уже развернутый проект. Авторские подписи на русском. Встроенные кнопки viewer и HTML language используют английский fallback установленной версии Archify.

```text
diagram_type: architecture
output: F:\ai-advent-challenge\ai-chat\rag\docs\diagrams\rag-week-architecture.html
specification_sha256: ba8a0a8f2e92908de66682607d35290f0502d85dc2203d6183829cf9a1850a82
specification_bytes: 1915
artifact_sha256: 6179ab0a7f6cc95185e7c39eeabed8bf2d4be95632565a6dd5d9fae3af254d0a
artifact_bytes: 804066
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 1
```

Одна точечная коррекция устранила наложение подписи JDBC на backend. После этого спецификация прошла validate и deliver. Автоматический browser check охватывает 1440×900, 1600×1000, 1920×1080, 2048×1320. Визуально просмотрены светлый 1440×900 и тёмный 2048×1320: компоненты, подписи, стрелки и легенда читаются, содержимое не обрезано.

Хеши связывают этот review с точными байтами HTML. Автоматический visual-check receipt не считается perceptual review: эти два результата зафиксированы отдельно.
