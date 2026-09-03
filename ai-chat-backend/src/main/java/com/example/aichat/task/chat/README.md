# Chat

Модуль реализует обычный диалог через `POST /api/chat`.

Поток данных: `ChatRequest` -> `ChatController` -> `ChatCommand` ->
`ChatService` -> input policy -> request guards -> LLM -> output policy -> `ChatResult` ->
`ChatResponse`.

HTTP DTO находятся только в `controller/dto`. `service` использует records из
`model` и поэтому не зависит от HTTP-слоя. История не хранится backend: клиент
передаёт её в каждом запросе, после чего `InputPolicy` проверяет лимиты и роли.

`ChatService` не знает конкретных JSON-схем: fallback и ограничения повторной
генерации принадлежат выбранной `OutputPolicy`.
