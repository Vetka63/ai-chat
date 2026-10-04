# Проверка схемы чата дня 25

Диаграмма отражает реально реализованные зависимости классов. Подготовка сама использует DeepSeek; это указано в карточке, а связь не дублирует генерацию grounded-ответа. Порядок сетевых действий подробно описан в [гайде](../day25-guide.md).

- diagram_type: architecture
- output: `F:/ai-advent-challenge/ai-chat/rag/docs/diagrams/day25-chat.html`
- specification_sha256: `fec609abd60fef36973627f5fd60cd9ac9e7de10ee34241781c845c19d2c99a6`, 2951 bytes
- artifact_sha256: `6d614d43cb52395cd59dbf2e344ec5e1faa7148b66b71774229c3fd45c620a83`, 806648 bytes
- validation: 9/9 showcase, 0 errors, 0 warnings
- browser_evidence: passed
- visual_review: passed
- correction_rounds: 2

Две точечные корректировки исправили диагностированные наложения подписей нижних зависимостей, не удаляя отношения. После final validate/deliver JSON и HTML заморожены. Packaged visual-check измерил containment при 1440×900, 1600×1000, 1920×1080, 2048×1320, снял light/dark при крайних размерах. Проверены изображения 2048×1320 обеих тем: текст, стрелки, подписи и карточки читаемы, пересечений узлов нет. Скриншоты и машинный receipt лежат рядом.

Материал на русском. Фиксированные элементы Viewer UI и html lang остаются English, потому что renderer не поддерживает русский locale. Автоматический visual-check receipt всегда оставляет visualReview=pending; оценка rendered screenshots выше — отдельная визуальная проверка, не изменение машинного receipt.
