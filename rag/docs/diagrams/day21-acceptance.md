# Archify: подтверждение дня 21

Актуальная схема реализации: `day21-architecture.html`. JSON: `day21-architecture.json`.

```text
diagram_type: architecture
specification_sha256: 74a456705550502dd42948692f85a00ae2076c6f5e5f8f2f568fc86425638994
artifact_sha256: 5d95be916af821b3139061457eb1b15697126d8e5714057e4a3658c4657c8ab8
specification_bytes: 1868
artifact_bytes: 803494
validation: 9/9 showcase, 0 errors, 0 warnings
browser_evidence: passed
visual_review: passed
correction_rounds: 1
```

Автоматический receipt `day21-architecture.visual-check.json` связан с exact SHA HTML. Проверены 1440×900, 1600×1000, 1920×1080, 2048×1320: нет horizontal/vertical desktop overflow. Endpoint screenshots сняты в light/dark. Отдельно image-capable reviewer просмотрел light 1440×900 и dark 2048×1320: текст не выходит из узлов, связи и подписи читаются, нет пересечения непричастного узла, сохраняется вертикальная композиция. Automated receipt сам по себе оставляет visualReview pending — эта ручная запись не изменяет его.

Русские подписи схемы; встроенный Viewer UI и html lang используют English fallback, поскольку Archify не поддерживает русский locale. Это не перевод приложения.

Схема показывает основной поток данных, не каждый обратный HTTP response. Стрелка SQLite → отчёт означает происхождение сохранённых метрик: фактически API читает SQLite, frontend получает comparison DTO и создаёт MD/JSON. База не вызывает экспорт напрямую и отдельного report-сервиса нет.

Файлы `rag-week-architecture*.html` и старые `acceptance*.md` сохранены как история проектирования недели. Они не являются схемой фактически реализованного дня 21; актуальные решения — `../architecture.md`, `../decisions.md` и эта схема. Не использовать старое предложение QASPER как описание текущего корпуса.
