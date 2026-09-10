# Сборка приложения и HTTP API

День 9: bootstrap подключает ContextMemory и LlmSummarizer к тому же агенту.
Lifespan создаёт отдельный SqliteSummaryRepository. PATCH conversations/{id}/context
сохраняет ContextSettings; POST conversations/{id}/fork создаёт независимую копию
истории без старых runs и summary. Оба действия не вызывают LLM. GET диалога
дополнительно возвращает summary. Возможности агента перечислены в AgentInfo.capabilities.

bootstrap.py — единственное место сборки агента, его политик и общих адаптеров.
settings.py читает локальные переменные окружения, ключи представлены SecretStr.
models.json — серверный allowlist моделей, контекстных окон и тарифов с источниками.

main.py управляет SQLite и HTTP-клиентом в lifespan. CRUD диалогов изолируется agent_id.
Маршруты не формируют LLM messages: это ответственность агента.

- GET /api/v1/models — безопасный каталог и модель по умолчанию.
- PATCH /api/v1/agents/{id}/conversations/{chat}/model — модель следующих сообщений.
- POST /api/v1/agents/{id}/preview — локальная оценка черновика; не пишет историю и не вызывает LLM.
- POST /api/v1/agents/{id}/runs — вызов агента. model_id можно передать с сообщением.
- GET /api/v1/agents/{id}/conversations/{chat} — история, выбранная модель и сохранённые runs.

Список/создание/удаление диалогов остаются совместимы с Днём 7.
У агента без preview подключаемая функция возвращает 501 accounting_disabled.
Отсутствующий usage отображается как неизвестный, не как бесплатный вызов.
Логи ошибок не содержат ключи, тела запросов или ответы модели.

