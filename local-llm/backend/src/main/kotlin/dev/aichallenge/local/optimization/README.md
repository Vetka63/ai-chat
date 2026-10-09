# Домен optimization — как устроен день 29

Внешний вход — `web/OptimizationController`: `/api/v1/optimization`.
Контроллер не знает о HTTP-протоколе LLM, весах модели и алгоритме оценки.

## Основные классы

- `config/OptimizationProperties`: активная локальная модель, путь отдельной SQLite,
  флаг облака и приватный API-ключ. Не является публичным DTO.
- `models/Contracts`: типы запроса/результата/метаданных, допустимые состояния и ограничения входа.
- `skills/PromptComposer`: единственное место сборки task instructions. Общий контракт одинаков
  у baseline/specialized. Специализация добавляет правила аккуратного извлечения и напоминание
  после входа. Skill добавляется отдельно и может применяться к любому provider.
- `evaluation/BenchmarkCatalog`: загружает 20 синтетических случаев, разворачивает детерминированный
  log noise, возвращает input и expected. Expected передаётся evaluator, но не генератору.
- `evaluation/CardEvaluator`: отклоняет Markdown, несколько JSON, повторные ключи и неверные типы;
  сравнивает ровно пять полей. Не исправляет результат и не делает семантическую оценку summary.
- `runtime/ModelGateway`: порт, позволяющий подменять провайдера в unit-тестах и будущем расширении.
- `runtime/ProviderGateway`: LOCAL → существующий runtime; DEEPSEEK → отдельный адаптер.
- `runtime/DeepSeekGateway`: один HTTP-запрос, явный cloud flag и allowlist, безопасные ошибки.
- `runtime/LocalProbe`: без генерации читает `/api/tags` и `/api/ps`.
- `runtime/AllocationMonitor`: отдельный короткоживущий sampler с sampled максимумами;
  измеряет allocation нужного окна, не RSS. Закрывается после каждого case.
- `storage/ExperimentRepository`: SQL prepared statements, отдельная SQLite,
  восстановление INTERRUPTED без повторного вызова модели. Каждому UUID соответствует один payload.
- `service/ExperimentService`: резервирует общий generation slot до HTTP 202, сохраняет snapshot,
  выполняет случаи в background последовательно, сохраняет каждый результат и освобождает slot.

## Пример полного пути

Пользователь выбирает case `c01`, LOCAL, specialized+skill, temperature=0.7, context=6144, max_tokens=512.
Клиент сохраняет UUID и делает POST. Сервис фиксирует, например, `qwen3:4b-q4_K_M`, фактический digest,
два сообщения SYSTEM/USER и их hash. Gateway отправляет в Ollama `num_ctx=6144`, `num_predict=512`.
Модель возвращает сырой JSON. Evaluator сравнивает service/environment/priority/time/cause,
не изменяя JSON. SQLite сохраняет raw answer, совпадения, длительность и model allocation.
UI показывает карточку и позволяет скачать MD без нового inference.

При `done_reason=length` сохраняется TRUNCATED, даже если итоговый answer пуст:
reasoning мог занять весь бюджет. Thinking и usage остаются в SQLite, UI и MD.
При нормальном stop пустой answer считается ошибкой. Экспорт не ремонтирует JSON;
сырые тексты помещаются в fenced-блоки, чтобы Markdown внутри входа не менял структуру отчёта.

После смены на FP16 UUID старого запроса всё ещё возвращает старый результат Q4. Новый запрос
получает новый UUID и новые model metadata. Сравнение честно только при тех же input и settings.

Параметры DeepSeek отличаются: локальные context/top_k/seed не отправляются; фактически применимые
значения доступны через `/experiments/{id}/parameters` и отражаются в MD.

Подробная методика, границы безопасности и ограничения — `local-llm/docs/day29-architecture.md`.
