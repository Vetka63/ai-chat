# Проверка архитектурной схемы дня 23

[HTML](day23-architecture.html) отражает доменные обращения одного Kotlin backend: ExperimentService координирует QueryRewriter, SearchService, CandidateSelector и AnswerGenerator. Это карта компонентов, не временная последовательность и не несколько микросервисов. Возвратные данные и все HTTP-adapter детали намеренно не дублируются линиями; точный порядок — в [гайде](../day23-guide.md).

```text
diagram_type: architecture
output: F:\ai-advent-challenge\ai-chat\rag\docs\diagrams\day23-architecture.html
specification_sha256: bee222372497656227d0d326ea04af6d02f73d39ebf4226e00317839a930d5bb
artifact_sha256: 3eb4d7f00093e434846ac2d259a1f1380f1a9729b1284e2c011cb9d1c788a1b0
specification_bytes: 1964
artifact_bytes: 803007
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 1
```

После одного исправления положения подписи validate и atomic deliver завершились с exit 0; specification после pass не редактировалась. Автоматический [visual-check receipt](day23-architecture.visual-check.json) связан с тем же SHA и размером HTML. Проверены READ/Still, light theme, размеры 1440×900, 1600×1000, 1920×1080 и 2048×1320: нет горизонтального или вертикального desktop overflow. Light/dark screenshots на крайних viewport сохранены рядом.

Отдельно image-capable reviewer просмотрел реальные light 1440×900 и dark 2048×1320 screenshots: подписи и узлы читаются, нет пересечений посторонних узлов/клиппинга, композиция использует вертикальную область. Этот perceptual review не изменяет visualReview=pending автоматического receipt. В receipt сохранён staging-путь исходной проверки; копирование в репозиторий не меняет байты артефакта.

Русские авторские подписи сохранены; фиксированный Viewer UI и html lang используют English fallback, поскольку русского locale в Archify нет. Схема не обещает reranking, semantic grounding или историю чата.
