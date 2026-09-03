# День 2: формат ответа

Домен реализует строгие форматы ответа из задания Дня 2. Здесь находятся
`AnswerJsonOutputPolicy`, `RecipeJsonOutputPolicy` и их DTO. Политики проверяют
JSON/schema после ответа DeepSeek, формируют fallback и задают ограничение для
повторной попытки при обрезанном или невалидном JSON.

Новый формат добавляется отдельной реализацией `OutputPolicy`; общий chat-сервис
находит её через `OutputPolicyRegistry` по настройке response mode.
