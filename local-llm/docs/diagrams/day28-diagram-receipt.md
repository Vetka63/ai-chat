# Приёмка архитектурной схемы дня 28

9 октября 2026. Archify 2.17, схема упрощена до пяти реальных компонентов.
Доказательство реализации — классы RagController, ExperimentService, RetrievalService,
RagRepository, OllamaEmbeddings и RagGenerators; диаграмма не описывает старый RAG Pro Git.

- diagram_type: architecture
- output: `day28-rag-overview.html`
- specification_sha256: `67418c516a02c7ec2e60b1fc5f969656fa61c55467f590aa04440c6f161069a0`
- artifact_sha256: `ee6d0eeec7a6ef5d7ab36d7c2f87343c2a6161a6b9b7207f6d76ca723645d5e6`
- specification bytes: 1483; artifact bytes: 799458
- validation: 9/9 showcase, 0 errors, 0 warnings
- browser_evidence: passed (`day28-rag-overview.visual-check.json`)
- visual_review: passed — просмотрены четыре фактических light/dark PNG на 1440×900 и 2048×1320;
  читаемые узлы, нет пересечений, обрезания текста и избыточной пустой нижней полосы.
- correction_rounds: 1 — исправлено только диагностированное положение подписи к DeepSeek.

Автоматический Chrome проход измерил 1440×900, 1600×1000, 1920×1080 и 2048×1320:
scrollWidth/scrollHeight не превышают viewport. Машинная проверка не заменяет визуальное ревью.
Просмотрены именно PNG из artifact-bound receipt, HTML после deliver не редактировался.
Содержание русское; служебный Viewer UI и html lang используют English fallback.
Локальные gitattributes фиксируют LF, чтобы checkout Windows не менял подтверждённые SHA256.

Предыдущий `day28-rag.architecture.json` остаётся историческим непринятым кандидатом;
готовая схема — только новый overview. Источники книги, env и SQLite в HTML не включены.
