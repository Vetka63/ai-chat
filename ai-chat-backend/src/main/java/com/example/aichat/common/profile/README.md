# Profile

Модуль владеет профилями агентов: общим системным промптом, input policy,
настройками DeepSeek, request guard, режимами ответа и непрозрачным
`feature_config`.

- `model` — неизменяемая модель профиля и общие конфигурационные records.
- `registry/AgentRegistry` — загружает и индексирует YAML-профили.
- `service/AgentLlmRequestFactory` — переводит настройки профиля в
  нейтральный запрос модуля `llm`.
- `validator/AgentFeatureConfigValidator` — точка расширения для настроек
  конкретного типа пространства.
- `controller` и `controller/dto` — публичные метаданные без промптов и секретов.

Новый тип пространства не добавляет поля в `AgentProfile`: его модуль сам
читает `feature_config` и реализует `AgentFeatureConfigValidator`.
