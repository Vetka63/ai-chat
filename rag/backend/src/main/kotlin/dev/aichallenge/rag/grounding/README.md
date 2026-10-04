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
| `models/GroundingRepair` | Исходная генерация и её REJECTED-проверка при единственной попытке исправления |
| `models/GroundedResult` | Snapshot ответа, статус, claims/sources и измерения всех стадий |
| `enums/GroundedStatus` | ANSWERED, UNKNOWN, INVALID_EVIDENCE, ERROR |

## Ограниченное исправление и trace

Исправление ограничено одной видимой попыткой: только если первый черновик прошёл `ExactCitationValidator`, а первый `supportCheck.status` равен `REJECTED`, Flash получает исходный запрос, те же переданные источники и feedback проверки. Новый вариант снова проходит дословную и смысловую проверку. Неверная форма JSON, транспортный сбой, truncation, UNKNOWN или ошибка точных цитат эту попытку не запускают; после неуспеха исправления следующей попытки нет. Это отдельная стадия работы с содержанием, не HTTP retry и не неограниченный LLM-цикл.

Nullable поле `GroundedResult.repair` сохраняет первоначально отклонённый черновик: `originalGeneration` и `originalSupportCheck`. Основные поля `generation` и `supportCheck` относятся к последнему варианту. Публичные claims/sources появляются только у последнего варианта, прошедшего обе проверки; первоначальный черновик остаётся в свёрнутой диагностике **«Исправление черновика · 1 попытка»**. Старые результаты без `repair` читаются как прежде. Каждая фактически выполненная генерация и проверка входит в API usage/cost ровно один раз, в том числе сохранённые внутри `repair`; при неизвестном расходе стадии общий расход неизвестен.

## Пример алгоритма

Пользователь спрашивает про `stash -u`. SearchService получает до 10 кандидатов. CandidateSelector оставляет до 5 с score ≥ 0.65; PromptAssembler.select пропускает только помещающиеся целые чанки. Модель видит их ID/текст и исходный вопрос, не rewritten query вместо вопроса. Она возвращает факт, chunk_id и цитату.

Валидатор находит chunk_id **только среди включённых чанков**. `indexOf(quote)` должен найти буквальный фрагмент; никакой нормализации ответа ради совпадения. `canonicalStart = chunk.start + offset`. Источник создаётся из metadata чанка.

После успешной проверки точности `ClaimSupportValidator` отдельно проверяет смысл каждого пункта. Он получает только claims, их цитаты и тексты цитируемых чанков. Окружающий текст помогает обнаружить потерянные условия книжного примера, но не заменяет недостающую цитату. Ни история чата, ни другой пункт ответа не могут служить доказательством. Проверяющая модель возвращает ровно один результат с индексом на каждый пункт: обязательны `claim_index`, `verdict`, `reason`, `evidence_scope`, `claim_scope`. Оба scope принимают только `general` или `example`. Необязательное поле `text` допустимо только как дословное эхо исходного claim; оно не может исправлять или заменять утверждение. Остальные лишние поля, изменённое эхо, дубликаты индексов/JSON-ключей, пропуски, неизвестные вердикты/scope и обрезанный JSON отклоняются. Только PASSED по всем пунктам разрешает собрать публичный ответ.

Классификация области действия отделена от verdict. Если checker пометил доказательство `evidence_scope=example`, а утверждение — `claim_scope=general`, сервер заменяет его `supported` на `unsupported`: частный пример не подтверждает общее правило, даже когда модель одновременно назвала его поддержанным. Scope выбирает сама модель и тоже может ошибаться; серверный gate проверяет согласованность объявленной области, а не доказывает правильность классификации. Поля scope остаются во внутреннем JSON/trace checker; публичные DTO и схема API не расширяются.

Если модель придумала ID, пересказала вместо цитирования или смысл итогового пункта не подтверждён после допустимого исправления, весь результат INVALID_EVIDENCE: claims/sources пусты. Такой же карантин действует при неправильном ответе проверяющей модели. Сбой её провайдера даёт ERROR без частичной публикации. Если score слабый — UNKNOWN ещё до генерации. Если нет ответа в сильном контексте — модель возвращает unknown с пустыми claims и уточнением; проверка смысла тогда не вызывается. Если контекст не помещается — ERROR; это не отсутствие знания.

Проверяются 1–8 пунктов, текст до 1500 символов, 1–3 цитаты по 20–600 символов. JSON не может содержать дополнительные поля `answer`, source или metadata. `finish_reason != stop` не принимается независимо от того, выглядит ли фрагмент JSON законченно. HTTP retry и baseline fallback отсутствуют; разрешена только описанная ниже ограниченная правка содержательно отклонённого черновика.

Ответ без исправления использует две LLM-стадии: генерацию и проверку смысла. Одна правка с повторной проверкой увеличивает максимум до четырёх без rewrite и до пяти с rewrite. Проверка вызывается через отдельный `LlmClient.completeVerifiedJson`: `DeepSeekLlmClient` использует `DeepSeekProperties.supportModel` (профиль Pro), `thinking.type=enabled` и отдельный `supportReasoningEffort`. Точные значения конфигурации опыта фиксируются в отчёте. Обычные `complete`/`completeJson` продолжают использовать Flash с выключенным thinking; nullable лимит пользовательского ответа не меняется. Default-реализация нового метода порта делегирует `completeJson`, сохраняя совместимость старых адаптеров и test fakes.

Технический лимит checker — **16384 completion tokens, включая reasoning**. Транспорт берёт полное `completion_tokens` провайдера, не вычитая и не добавляя reasoning отдельно. `supportCheck.generation` сохраняет messages, финальный raw JSON из `content`, finish reason, usage и оценку стоимости даже при отклонении вердиктов; `reasoning_content` и ключ не сохраняются и не логируются. Сумма включает каждую выполненную стадию один раз; при неизвестном usage любой стадии общая сумма остаётся неизвестной. Транспортный сбой до ответа провайдера оставляет supportCheck пустым, но учитывается в числе попыток. Для checker задан отдельный HTTP timeout 180 секунд (`DEEPSEEK_SUPPORT_TIMEOUT_SECONDS`); обычный timeout генерации остаётся 120 секунд.

Отдельная модельная проверка снижает риск, но не доказывает истинность книги, полноту ответа или безошибочность проверяющей модели. Ручная оценка контрольных вопросов остаётся необходимой. `ExactCitationValidator` по-прежнему отвечает только за дословность; его успешный внутренний результат отдельно не разрешает публикацию. Новое nullable поле `supportCheck` по умолчанию null позволяет прочитать старые сохранённые результаты; наличие старого ANSWERED без этого поля не означает прохождение новой проверки.

Подробный сценарий UI и статусы: [гайд](../../../../../../../../docs/day24-guide.md). Проверки: `GroundingTest`, `ClaimSupportTest`, `DeepSeekAdapterTest`, `client/tests/grounding.spec.ts`, `scripts/verify-day24.ps1`. Каждый live-прогон сохраняется отдельно в `data/day24-live-results-<UTC timestamp>-<run id>.json`; исторические traces не перезаписываются.

Opt-in `ClaimSupportLiveTest` содержит десять случаев настоящего checker, без генерации ответа, rewrite и retry. Включение — `RAG_RUN_LIVE_SUPPORT=true`; ключ — `DEEPSEEK_API_KEY` с fallback на `LLM_API_KEY`; модель и effort теста можно явно переопределить через `RAG_SUPPORT_TEST_MODEL` и `RAG_SUPPORT_TEST_EFFORT`. Отдельный trace — `data/day24-support-live-<timestamp>-<run id>.json`. Наличие fixture не означает прохождение живой проверки: фактические результаты и выбранный профиль приведены в [финальном отчёте](https://github.com/Vetka63/ai-chat/blob/ai-challenge-day-25/rag/docs/final-live-verification.md).
