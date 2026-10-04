# Домен embeddings

`EmbeddingProvider` отделяет приложение от HTTP runtime. `EmbeddingBatch` возвращает вектора, фактический usage input tokens, если runtime его сообщил, и измеренное время.

`OllamaEmbeddingProvider`:

1. `/api/tags` подтверждает установленную модель и digest.
2. Dimension probe определяет размерность фактически возвращаемого вектора.
3. `/api/embed` получает список текстов с `truncate=false`.
4. Проверяется model name, vector count, одинаковая dimension, числовые конечные координаты и ненулевая норма.
5. Ошибки сети/HTTP/JSON/векторов становятся понятными кодами, а не фиктивными embeddings.

Время первого batch может включать прогрев GPU. Dimension probes не входят в сохранённый batch usage, что явно отмечено в UI/docs. День 21 не считает API-стоимость DeepSeek: его нет в этом потоке.

Идентичность space: model + digest + dimension + версия document template. Индекс и поисковый вопрос должны использовать одинаковый space. Новая модель требует перестроения. Не делаем fallback на другую модель при ошибке: результат выглядел бы успешным, но сравнение было бы некорректным.

Qwen query template содержит retrieval instruction, документы — заголовок/section/текст. Это embedding input, не системный промпт генеративного агента. Версия обоих шаблонов связана с контрактом индекса и должна измениться при их изменении.
