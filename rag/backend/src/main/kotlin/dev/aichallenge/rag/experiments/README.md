# experiments: контролируемое сравнение дня 23

`ExperimentController` принимает один POST `/api/v1/experiments/compare`. `ExperimentRequest` фиксирует исходный вопрос, выбранный индекс, режимы и ограничения. Повторяющиеся режимы, final K больше candidate K, нечисловой/вне диапазона порог и неверные budgets отклоняются до LLM.

`ExperimentService` координирует работу доменов, но не реализует embeddings, SQL или провайдерский HTTP. Сначала проверяет индекс; затем выполняет максимум один QueryRewriter и два SearchService-вызова. RAW/FILTERED используют общий исходный пул, REWRITE/REWRITE_FILTERED — общий rewritten пул. Отдельные виртуальные потоки JDK 21 выполняют максимум четыре генерации в одном запросе; результат сохраняет порядок запрошенных режимов.

`runMode` применяет CandidateSelector, затем PromptAssembler и AnswerGenerator. Пустой отбор — NO_CONTEXT без генерации. Непустой отбор при `generateAnswers=false` — RETRIEVED. Невмещающийся контекст — ERROR с trace, без LLM. Успешная генерация — ANSWERED; её ошибка остаётся только в соответствующей колонке.

`RetrievalTrace` содержит исходный вопрос, фактический search query, raw/selected candidates, причины решения, threshold и embedding usage. `ExperimentResult` хранит mode/status, безопасную ошибку, trace, nullable AnswerResult и generationAttempted. `ExperimentComparison` хранит общий RewriteTrace, snapshot/config, независимые результаты, общий latency и usage/cost. Если не все предпринятые LLM-стадии вернули usage, полная сумма неизвестна (null), а доступные отдельные измерения остаются видимыми. Rewrite не дублируется в расходе колонок.

LLM-стадии — логические попытки этого запроса, не billing ledger провайдера. Частичная или потерянная сетевая операция может иметь неизвестный расход. Сервис не делает retry, не подменяет rewrite исходным query скрытым fallback, не переиндексирует книгу и не сохраняет чат.

Параметры режима — enum `RetrievalMode`; статусы — `ExperimentStatus`. Порт CandidateSelector относится к retrieval, QueryRewriter — к rewriting, генерация — к answering. Новый reranker можно подключить в реализации отбора без копирования оркестратора. Новый эксперимент не требует расширять общий пакет `common` бизнес-DTO.

Сценарии пользователя и ограничения: [day23-guide.md](../../../../../../../../docs/day23-guide.md). Тесты — `ExperimentTest`: граница threshold, лимиты, бюджет, ошибки rewrite/поиска/генерации, общий пул, неизменный вопрос, расходы и preview без LLM.
