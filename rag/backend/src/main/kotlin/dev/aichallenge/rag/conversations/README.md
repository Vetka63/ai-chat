# Сохраняемые диалоги RAG

Пакет управляет жизненным циклом чатов, не реализует поиск и не проверяет цитаты самостоятельно. Он использует `taskmemory` для подготовки контекста и `grounding` для свежего ответа по книге.

## Карта классов

| Класс | Назначение |
| --- | --- |
| ConversationController | GET/POST/DELETE `/api/v1/conversations`, POST `/{id}/turns` |
| ConversationService | Порядок begin → prepare → grounded answer → finish; ограничение хвоста и учёт расхода |
| ConversationRepository | Порт атомарного begin/finish и чтения истории отдельно от транспорта |
| SqliteConversationRepository | SQLite, foreign keys/cascade, immediate transactions, revision, idempotency и recovery |
| ConversationSettings | Индекс, K, threshold, source budget, окно истории и nullable output cap |
| ConversationTurn | Сохранённый user turn, статус, preparation/result, memoryAfter и наблюдение |
| TurnStatus | PENDING до сети, COMPLETED после finish, INTERRUPTED после рестарта |

## Как проходит сообщение

1. Контроллер проверяет размер/тип запроса, сервис — межполевые правила.
2. `begin` в короткой write-транзакции проверяет requestId, pending и revision. Сохраняет вопрос до API. Дубликат ID с тем же вопросом возвращает запись, не запускает новый флоу; другой вопрос с тем же ID получает 409.
3. Сервис читает последние до 6 обменов и до 10000 символов по умолчанию. Не разрезает текст ответа. При превышении бюджета прекращает подбор более старых обменов и показывает число пропущенных; полная история остаётся.
4. `DialoguePreparer` получает этот хвост, прежнюю память и текущий вопрос. Невалидная подготовка останавливает флоу, не меняя memory.
5. `GroundingService` получает original question и internal GroundingDialogue с resolved query. Свежий embedding/search происходит для каждого успешно подготовленного turn. Вызов старого QueryRewriter здесь не дублируется.
6. CitationValidator проверяет только новые included chunks. Старые ответы и memory нельзя цитировать вместо книги.
7. `finish` одной транзакцией сохраняет результат, trace и memoryAfter, затем обновляет отдельную таблицу task_memory и revision. Даже UNKNOWN/INVALID/ERROR сохраняют валидно извлечённые пользовательские факты.

## Хранение и ошибки

`conversations` содержит настройки, snapshot ID, title, revision и timestamps. `conversation_turns` содержит полный payload каждого обмена, включая безопасную диагностику. `task_memory` содержит только текущую память одного conversation_id. Все три находятся в том же SQLite-файле, что индекс, но в отдельных таблицах. Удаление чата каскадно удаляет только его turns/memory, не документы и вектора.

Read detail выполняется в read-транзакции, поэтому response не смешивает историю одной ревизии и память другой. BEGIN IMMEDIATE сериализует короткие write-операции без сетевых вызовов под SQL-lock. Один pending обеспечен также partial unique index.

При перезапуске единственного локального backend pending меняется в INTERRUPTED. Вопрос остаётся видимым; его можно задать заново вручную. Отсутствующий usage нельзя заменить нулём: totalUsage=null, хотя доступные метрики стадии сохраняются. Стоимость — сумма известных оценок, не списание баланса.

Для будущей многопользовательской версии нужны authentication/ownership, limits хранения, lease для нескольких replicas и фоновые resumable jobs. В текущей учебной версии локальные API доступны любому процессу с доступом к localhost.
