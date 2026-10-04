# API contract

`openapi.json` описывает текущий HTTP интерфейс дня 21 и его DTO. Backend отдаёт его как `/api-spec/openapi.json`. Клиент генерирует `src/api/schema.d.ts` через openapi-typescript при build.

Основные ресурсы: `/api/v1/corpus`, `/api/v1/documents`, `/api/v1/indexes`, `/api/v1/jobs`. `/api/v1/preview` синхронный, построение индекса — POST `/api/v1/jobs` с HTTP 202, progress GET `/api/v1/jobs/{id}`. Search — POST query конкретного готового index ID. Comparison — `/api/v1/compare`, два distinct IDs.

Путь к документу, размерность и модель клиент не задаёт. Validation ошибки — 400, неизвестные IDs — 404, busy — 409, runtime unavailable — 503; JSON имеет `code` и `message`.

Не считайте наличие schema самостоятельным доказательством соответствия runtime: проверки выполняются также на настоящем HTTP и браузере. При изменении DTO обновляйте schema и тесты одновременно. Сведения о future API не смешиваются с текущими эндпоинтами.
