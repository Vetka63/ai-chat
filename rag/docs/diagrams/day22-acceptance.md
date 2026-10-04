# Проверка архитектурной схемы дня 22

Схема [day22-architecture.html](day22-architecture.html) отражает доменные вызовы двух режимов ответа. Исходная спецификация — [day22-architecture.json](day22-architecture.json). Проверки артефакта, браузера и визуального качества фиксируются отдельно.

```text
diagram_type: architecture
output: F:\ai-advent-challenge\ai-chat\rag\docs\diagrams\day22-architecture.html
specification_sha256: 4d7e1034415c329728537c78a67e7f7d1e36d9575af9b1fff195406c59c34bf1
artifact_sha256: cb2134f17919bc1e1d4608aa8e6a7fa66815efea52356715739e82c5b89de00a
specification_bytes: 1901
artifact_bytes: 803600
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 2
```

Atomic deliver завершился с exit 0. Автоматический [visual-check receipt](day22-architecture.visual-check.json) связан с тем же SHA HTML и размером 803600 bytes. Проверены light theme, READ/Still, размеры 1440×900, 1600×1000, 1920×1080 и 2048×1320: нет горизонтального или вертикального desktop overflow. На двух крайних viewport сохранены light/dark screenshots. HTML и JSON скопированы в репозиторий без изменения байтов; путь staging в автоматическом receipt обозначает место первоначальной проверки.

Отдельно image-capable reviewer просмотрел реальные light 1440×900 и dark 2048×1320 screenshots: узлы и подписи читаются, линии не проходят через посторонние узлы, нет клиппинга, вертикальная композиция сбалансирована. Эта запись о perceptual review не меняет visualReview=pending внутри автоматического receipt: браузерные измерения сами по себе не оценивают красоту.

Русские подписи сохранены. Фиксированный Viewer UI и html lang используют English fallback, поскольку русского locale в Archify нет. SearchService и AnswerService — модули одного Kotlin backend, не два микросервиса. SQLite не вызывает LLM; схема показывает обращения и не рисует все обратные ответы. BASELINE минует поиск и Ollama, RAG использует сохранённые чанки и локальный embedding вопроса.
