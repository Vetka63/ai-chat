# API contract

`openapi.json` описывает HTTP интерфейс дней 21–24 и DTO. Backend отдаёт его как `/api-spec/openapi.json`. Клиент генерирует `src/api/schema.d.ts` через openapi-typescript при build.

День 22: POST `/api/v1/answers`, GET `/api/v1/answer-settings`, GET `/api/v1/evaluation/questions`. BASELINE не требует индекса; RAG требует готовый indexId. Бюджет — символы текстов целых чанков. `maxOutputTokens=null` не отправляет лимит провайдеру. Ошибки upstream — 502/503. Объекты usage/cost nullable, неизвестные измерения не заменяются нулём.

День 23: POST `/api/v1/experiments/compare`. ExperimentRequest принимает 1–4 различных mode: RAW, FILTERED, REWRITE, REWRITE_FILTERED; candidateTopK 1–20; finalTopK 1–10 и не больше candidate K; конечный cosine threshold [-1,1]; бюджет 300–60000; nullable output cap. Общая конфигурация и snapshot ID возвращаются вместе с одним RewriteTrace и независимыми result/status/error/pipeline/answer. Некорректная конфигурация — 400, неизвестный index — 404; ошибки отдельных стадий возвращаются в соответствующем результате batch. Это не успешный ответ модели: клиент обязан читать status каждой колонки.

generateAnswers=false пропускает генерацию, но rewrite-режимы всё ещё вызывают LLM для подготовки query. NO_CONTEXT всегда означает отсутствие отобранных чанков и отсутствие генерации. TotalUsage nullable при неполном измерении; общий rewrite входит в сумму один раз. Старые DTO/endpoint дня 22 не заменяются.

Основные ресурсы: `/api/v1/corpus`, `/api/v1/documents`, `/api/v1/indexes`, `/api/v1/jobs`. `/api/v1/preview` синхронный, построение индекса — POST `/api/v1/jobs` с HTTP 202, progress GET `/api/v1/jobs/{id}`. Search — POST query конкретного готового index ID. Comparison — `/api/v1/compare`, два distinct IDs.

День 24: POST `/api/v1/grounded-answers`, `GroundingRequest` → `GroundedResult`. Нужен готовый indexId, K/порог/бюджет; rewrite выключен по умолчанию, nullable output cap не отправляется. Ответ содержит status, claims с VerifiedCitation, EvidenceSource, snapshotId и trace. Читайте status: UNKNOWN, INVALID_EVIDENCE и ERROR не являются ответами с доказательствами. Claims/sources в них пусты, без придуманных источников. В ANSWERED поле answer собирается сервером из claims; метаданные и координаты не приходят от модели. Raw JSON только diagnostic, не публичный ответ. HTTP 400/404 сохраняют общий контракт; ошибки внутренних стадий помещаются в result. Старые endpoints не изменены.

Путь к документу, размерность и модель клиент не задаёт. Validation ошибки — 400, неизвестные IDs — 404, busy — 409, runtime unavailable — 503; JSON имеет `code` и `message`.

Не считайте наличие schema самостоятельным доказательством соответствия runtime: проверки выполняются также на настоящем HTTP и браузере. При изменении DTO обновляйте schema и тесты одновременно. Сведения о future API не смешиваются с текущими эндпоинтами.
