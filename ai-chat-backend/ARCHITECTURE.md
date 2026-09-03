# Архитектура Java backend

Backend — модульный монолит Spring Boot. Код разделён по заданиям и бизнес-
возможностям, поэтому новая задача добавляется по одной вертикальной оси, а не
разбрасывается по глобальным каталогам `controller`, `service` и `dto`.

## Структура

```text
com.example.aichat
├── config/                         техническая конфигурация Spring
├── bootstrap/                      проверка связей при запуске
├── common/                         стабильные переиспользуемые контракты
│   ├── profile/                    профили агентов и их registry
│   ├── llm/                        нейтральный LLM port + DeepSeek adapter
│   ├── inputpolicy/                InputPolicy + registry + стандартная политика
│   ├── outputpolicy/               OutputPolicy + registry + text policy
│   ├── validator/                  RequestGuard + registry
│   ├── llmjudge/                   LlmJudge + типизированный registry
│   ├── enums/                      действительно общие enum
│   └── exception/                  единая обработка HTTP-ошибок
└── task/                           независимые вертикальные задачи
    ├── chat/                       основной HTTP-сценарий чата
    ├── recipe/                     классификация кулинарного запроса
    ├── day2/responseformat/         строгие JSON output-policy
    └── day3/reasoning/              четыре стратегии и LLM-as-judge
```

В Java-именах используются `inputpolicy`, `outputpolicy` и `llmjudge`, потому
что дефис недопустим в имени package. Внутри задачи создаются только реально
нужные подпакеты: пустые `controllers/services/validators` не добавляются.

## Правила зависимостей

- `common` не импортирует ни одну конкретную `task`.
- Одна задача не импортирует другую задачу; общий код сначала извлекается в
  небольшой контракт в `common`.
- DTO HTTP-контроллера остаются в `controller/dto`; сервисы получают внутренние
  command/model и не зависят от HTTP.
- `LlmClient` работает с `LlmCompletionRequest` и не знает о профилях и днях.
- `bootstrap` может видеть модули для fail-fast проверки, но модули не знают о
  `bootstrap`.
- Архитектурный тест контролирует пути package, циклы, запрещённые зависимости,
  старые layer-пакеты и наличие документации.

Конфигурационные enum находятся в `config/enums`, task-specific enum — внутри
`task/<name>/enums`, а в `common/enums` остаются только общие значения.

`config` является техническим leaf-модулем с runtime-настройками. Он не
содержит бизнес-правил. `common` и `task` могут читать `ChatProperties`; обратной
зависимости `config -> task` нет.

## Гибкие точки настройки

Профиль YAML выбирает поведение через идентификаторы:

- `input_policy.type` → `InputPolicyRegistry`;
- `request_guard.type` → `RequestGuardRegistry`;
- `response_modes[].output_policy` → `OutputPolicyRegistry`;
- task-specific judge → `LlmJudgeRegistry` с проверкой input/result типов;
- `experience_type` + `feature_config` → конфигурация конкретной задачи.

Это позволяет отдельно менять формирование input, валидацию, разбор output и
LLM-as-judge, не переписывая центральный chat pipeline.

## Как добавить новый день

1. Создать `task/dayN/<feature>` и соседний `README.md`.
2. Добавить только используемые пакеты: `controller`, `service`, `validator`,
   `inputpolicy`, `outputpolicy`, `llmjudge`, `enums`, а DTO держать у границы.
3. Реализовать общий extension interface либо собственный сервис задачи.
4. Добавить профиль с новым `experience_type` и типизированным `feature_config`.
5. Добавить unit/HTTP тесты и при необходимости расширить архитектурные правила.

Публичные URL и JSON-контракты при этом рефакторинге не менялись:
`/api/chat`, `/api/profiles`, `/api/reasoning-experiments` и
`/api/reasoning-experiments/judge` остаются совместимыми с клиентом.
