# grounding — домен дня 24

Домен независимых вопросов с источниками. Не меняет AnswerService дня 22 и ExperimentService дня 23; переиспользует поиск, отбор, упаковку, LLM-порт и оценку стоимости. Контроллер не содержит поиск или разбор JSON.

| Класс | Ответственность |
|---|---|
| `controllers/GroundingController` | POST `/api/v1/grounded-answers`, Bean Validation |
| `models/GroundingRequest` | Исходный вопрос, индекс, K, порог, бюджет, rewrite и nullable output cap |
| `services/GroundingService` | Порядок стадий, gate, ошибки, trace, usage и сборка публичного ответа |
| `services/GroundingPromptAssembler` | Инструкция JSON и исходный вопрос с фактическим book_context |
| `ports/CitationValidator` | Заменяемая проверка результата по переданным SearchHit |
| `adapters/ExactCitationValidator` | Fail-closed схема и точные substring-цитаты |
| `models/GroundedClaim` | Один публичный пункт и 1–3 проверенные цитаты |
| `models/EvidenceSource` | Source, title, section и IDs, полученные сервером |
| `models/VerifiedCitation` | Текст цитаты и диапазоны UTF-16 чанка/канонического документа |
| `models/EvidenceValidation` | Внутренний результат проверки, не отдельный REST DTO |
| `models/EvidenceIssue` | Код отказа и индексы проблемного пункта/цитаты |
| `models/GroundingRetrieval` | Найдено → отобрано → реально передано; исключения по бюджету |
| `models/GroundingGeneration` | Фактические messages, raw JSON, usage и finish reason; диагностика не публичное утверждение |
| `models/GroundedResult` | Snapshot ответа, статус, claims/sources и измерения всех стадий |
| `enums/GroundedStatus` | ANSWERED, UNKNOWN, INVALID_EVIDENCE, ERROR |

## Пример алгоритма

Пользователь спрашивает про `stash -u`. SearchService получает до 10 кандидатов. CandidateSelector оставляет до 5 с score ≥ 0.65; PromptAssembler.select пропускает только помещающиеся целые чанки. Модель видит их ID/текст и исходный вопрос, не rewritten query вместо вопроса. Она возвращает факт, chunk_id и цитату.

Валидатор находит chunk_id **только среди включённых чанков**. `indexOf(quote)` должен найти буквальный фрагмент; никакой нормализации ответа ради совпадения. `canonicalStart = chunk.start + offset`. Источник создаётся из metadata чанка. Только после проверки всех пунктов сервис собирает ответ из их текста.

Если модель придумала ID или пересказала вместо цитирования, весь результат INVALID_EVIDENCE: claims/sources пусты. Если score слабый — UNKNOWN ещё до генерации. Если нет ответа в сильном контексте — модель возвращает unknown с пустыми claims и уточнением. Если контекст не помещается — ERROR; это не отсутствие знания.

Проверяются 1–8 пунктов, текст до 1500 символов, 1–3 цитаты по 20–600 символов. JSON не может содержать дополнительные поля `answer`, source или metadata. `finish_reason != stop` не принимается независимо от того, выглядит ли фрагмент JSON законченно. Retry и baseline fallback отсутствуют.

Точная цитата не гарантирует смысловую правильность пункта. Сегодня смысл проверен вручную на контрольных вопросах; при дальнейшей задаче можно заменить/дополнить порт отдельной semantic-проверкой. Не следует считать текущий валидатор полноценным judge.

Подробный сценарий UI и статусы: [гайд](../../../../../../../../docs/day24-guide.md). Проверки: `GroundingTest`, `DeepSeekAdapterTest`, `client/tests/grounding.spec.ts`, `scripts/verify-day24.ps1`.
