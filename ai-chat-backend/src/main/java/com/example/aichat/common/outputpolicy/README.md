# Output

Модуль владеет валидацией, нормализацией и fallback-представлением ответа LLM.

- `OutputPolicy` — расширяемый контракт.
- `OutputPolicyRegistry` — разрешает policy по идентификатору из YAML.
- `TextOutputPolicy` — общая реализация обычного текста.
- `model` — нейтральный результат применения policy.

Специфичные JSON policy и payload Дня 2 находятся в
`task/day2/responseformat/outputpolicy`.

Структурированная policy сама сообщает, нужна ли повторная генерация и какое
ограничение добавить к retry-промпту. Поэтому новая JSON-схема добавляется новой
policy и моделью без `switch` в `ChatService`.
