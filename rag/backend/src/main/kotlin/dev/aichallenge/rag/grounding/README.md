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
| `ports/ClaimSupportValidator` | Отдельная проверка поддержки каждого пункта его собственными цитатами |
| `services/ClaimSupportPromptAssembler` | Claims, их цитаты и контекст только цитируемых чанков без истории, памяти и эталонных ответов |
| `adapters/LlmClaimSupportValidator` | Один JSON-вызов модели; строгие индексы и вердикты, отказ при потере условий или противоречии |
| `models/ClaimSupportAssessment`, `models/ClaimSupportCheck` | Вердикты по пунктам, причины и полный trace проверяющей стадии |
| `enums/SupportCheckStatus`, `enums/ClaimSupportVerdict` | Результат стадии PASSED/REJECTED/INVALID_RESPONSE и SUPPORTED/UNSUPPORTED/CONTRADICTED по пунктам |
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

Валидатор находит chunk_id **только среди включённых чанков**. `indexOf(quote)` должен найти буквальный фрагмент; никакой нормализации ответа ради совпадения. `canonicalStart = chunk.start + offset`. Источник создаётся из metadata чанка.

После успешной проверки точности `ClaimSupportValidator` отдельно проверяет смысл каждого пункта. Он получает только claims, их цитаты и тексты цитируемых чанков. Окружающий текст помогает обнаружить потерянные условия книжного примера, но не заменяет недостающую цитату. Ни история чата, ни другой пункт ответа не могут служить доказательством. Проверяющая модель возвращает ровно один вердикт с индексом на каждый пункт; дубликаты индексов/JSON-ключей, пропуски, лишние поля, неизвестные вердикты и обрезанный JSON отклоняются. Только PASSED по всем пунктам разрешает собрать публичный ответ.

Если модель придумала ID, пересказала вместо цитирования или смысл пункта не подтверждён его цитатами, весь результат INVALID_EVIDENCE: claims/sources пусты. Такой же карантин действует при неправильном ответе проверяющей модели. Сбой её провайдера даёт ERROR без частичной публикации. Если score слабый — UNKNOWN ещё до генерации. Если нет ответа в сильном контексте — модель возвращает unknown с пустыми claims и уточнением; проверка смысла тогда не вызывается. Если контекст не помещается — ERROR; это не отсутствие знания.

Проверяются 1–8 пунктов, текст до 1500 символов, 1–3 цитаты по 20–600 символов. JSON не может содержать дополнительные поля `answer`, source или metadata. `finish_reason != stop` не принимается независимо от того, выглядит ли фрагмент JSON законченно. Retry и baseline fallback отсутствуют.

Успешный ответ без rewrite использует две LLM-стадии: генерацию и проверку смысла; rewrite добавляет третью. Проверяющая стадия имеет технический лимит **2048 output tokens**, не меняющий nullable лимит пользовательского ответа. `supportCheck.generation` сохраняет её messages, raw JSON, finish reason, usage и оценку стоимости даже при отклонении вердиктов. Сумма включает каждую выполненную стадию один раз; при неизвестном usage любой стадии общая сумма остаётся неизвестной. Транспортный сбой до ответа провайдера оставляет supportCheck пустым, но учитывается в числе попыток.

Отдельная модельная проверка снижает риск, но не доказывает истинность книги, полноту ответа или безошибочность проверяющей модели. Ручная оценка контрольных вопросов остаётся необходимой. `ExactCitationValidator` по-прежнему отвечает только за дословность; его успешный внутренний результат отдельно не разрешает публикацию. Новое nullable поле `supportCheck` по умолчанию null позволяет прочитать старые сохранённые результаты; наличие старого ANSWERED без этого поля не означает прохождение новой проверки.

Подробный сценарий UI и статусы: [гайд](../../../../../../../../docs/day24-guide.md). Проверки: `GroundingTest`, `ClaimSupportTest`, `DeepSeekAdapterTest`, `client/tests/grounding.spec.ts`, `scripts/verify-day24.ps1`. Каждый live-прогон сохраняется отдельно в `data/day24-live-results-<UTC timestamp>-<run id>.json`; исторические traces не перезаписываются.
