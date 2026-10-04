# Проверка схемы исправленного RAG-чата

- kind: architecture; preset: web-app; status: delivered
- source: `verified-chat.architecture.json`
- specification_sha256: `b3cfe0891b6efb06333e3aa58c358d78b16f25f54fe971566e58f6a54ae2b9fc`
- artifact: `verified-chat.html`
- artifact_sha256: `a003d9ee891a0ca316c80f953400c879edd6a2a31b81485a7afdb0eef74cad23`
- artifact_bytes: 808414
- showcase_checks: 9/9, errors: 0, warnings: 0
- correction_rounds: 2
- browser_status: passed
- visual_review: passed

Archify создал standalone HTML с inline SVG. Проверены 1440×900, 1600×1000, 1920×1080 и 2048×1320; endpoint screenshots сохранены для light/dark. Визуально просмотрены light 1440×900 и dark 2048×1320: узлы, стрелки и карточки читаемы, наложений и обрезания нет. После проверки байты JSON/HTML не изменялись; копирование в репозиторий сохраняет хеши.

Машинный receipt [visual-check](verified-chat.visual-check.json) содержит staging-путь первоначальной проверки. Его `visualReview=pending` не выдаётся за ручное заключение — ручная оценка зафиксирована отдельно здесь. Авторские подписи русские, стандартный viewer английский. Схема показывает исправленный флоу дня 25: receipt и pending → подготовка памяти/поиска → новые чанки → генерация → точность → смысловая поддержка → атомарное сохранение. Она не обещает безошибочность модельных проверок.
