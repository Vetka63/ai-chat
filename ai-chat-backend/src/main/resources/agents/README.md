# YAML-профили агентов

Каждый `.yml` описывает одно клиентское пространство. Общие поля читаются
`AgentProfile`; секреты в эти файлы не записываются.

Основные разделы:

- `system_prompt` — серверная роль агента;
- `input_policy` — лимиты входа и истории;
- `request_guard` — необязательная семантическая проверка;
- `deepseek` — модель и параметры генерации;
- `response_modes` — формат, output policy, токены и stop;
- `experience_type` — frontend/backend обработчик пространства;
- `feature_config` — непрозрачная для общего профиля конфигурация конкретной feature.

На старте `AgentConfigurationVerifier` проверяет у каждого включённого профиля
ссылки на validators, output policies, тип пространства и его типизированную
конфигурацию. Новый сложный день должен хранить свои поля только в
`feature_config`, а не расширять `AgentProfile`.

Например, `day4-temperature.yml` хранит в `feature_config.temperatures` весь
набор вариантов эксперимента. `day3-reasoning.yml` содержит отдельный блок
`feature_config.judge.llm`: он меняет модель только у судьи, сохраняя общие
DeepSeek endpoint и API-ключ.
