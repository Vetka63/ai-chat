# Контрольная точка реализации дня 29

8 октября 2026, Europe/Samara. Пользователь выбрал: «Пока сохраним checkpoint и продолжим позже».
Не запускать новые LLM-вызовы, скачивания, сборки или восстановление Docker без новой команды.

## Git и папка

- Репозиторий: `F:\ai-advent-challenge\ai-chat`.
- Рабочая ветка: `ai-challenge-day-29`, создана от `ai-challenge-day-27`, родитель `a05403c`.
- День 28 сохранён отдельным локальным checkpoint-коммитом `a42a1f1` в `ai-challenge-day-28`.
  Исправлено правило `/corpus/`, которое раньше исключало также исходник CorpusService.
- Main и предыдущие ветки не изменялись. Push не выполнялся.
- День 29 сохраняется локальным checkpoint-коммитом вместе с этим файлом. Это незавершённая реализация, не финальный релиз.

## Реализовано

Изолированный домен `local-llm/backend/src/main/kotlin/dev/aichallenge/local/optimization`:
config, models, skills, evaluation, runtime, storage, service, web.
REST prefix `/api/v1/optimization`; отдельная `/app/data/optimization.sqlite`.
20 синтетических incident cases: 10 calibration, 10 holdout. История/RAG не используются.
Baseline и specialized task prompts; подключаемый `incident-triage` skill версии 2.
Промпт-контракт версии `incident-card-v3`. Эталон никогда не передаётся модели.

LOCAL и явный DeepSeek API. Один активный local model задаётся `OPT_LOCAL_MODEL` при запуске.
Планируемые варианты: `qwen3:4b-q4_K_M`, `qwen3:4b-fp16`.
temperature, max tokens, context, thinking, top_p/top_k/seed; облачные ограничения учтены явно.
Нет скрытого judge, исправления ответов, cloud fallback, очередей и повторов генерации.
Асинхронный POST 202, UUID-квитанция/idempotency, последовательный suite, общий GenerationGate.
Точные сообщения/hash, модель/digest/quantization/runtime version, raw answer, errors, usage,
sampled allocation нужного окна, schema и пять эталонных полей сохраняются на сервере.
Перезапуск помечает незавершённое INTERRUPTED и сохраняет завершённые cases.

Vue UI: `client/src/features/optimization`; адрес `http://localhost:8483/#optimization`.
Выбор provider/settings/skill/cases; результаты сбоку; ответы и метрики рядом;
сравнение сохранённых запусков между сменами модели; warnings при смешении параметров;
вывод пользователя и MD-экспорт. Старые экраны 26–27 остаются отдельными.

Скрипты: `scripts/start-day29.ps1`, `scripts/verify-day29.ps1`.
Документы: `docs/day29-architecture.md`, `docs/day29-video-guide.md`, `docs/day29-verification.md`,
`backend/src/main/kotlin/dev/aichallenge/local/optimization/README.md`.

## Где остановились

Подробные цифры и ограничения находятся в `day29-verification.md`.
38 backend-тестов прошли в Docker до последних малых правок runtime error text.
Актуальные 22 frontend-теста и production build прошли локально; host Node 22.11 ниже поддерживаемой версии.
Две high-severity зависимости из npm audit требуют отдельного просмотра, автоматических upgrade не было.

Q4 установлен, завершены 111 локальных попыток и 3 paid DeepSeek smoke.
Calibration улучшилась с 3/10 до 9/10 точных карточек. Holdout baseline: 6/30, specialized 4096: 24/30.
Три h10 не поместились в окно 4096; h06 ошибочно получил P1 вместо P2.
Финальный preset после проверки объёма увеличен до 6144 без изменения prompt по контрольным ответам.
Повтор этого профиля и полный DeepSeek holdout **не стартовали**: сборка прервалась при сбое Docker.
FP16 скачивался (примерно 7,04/8,05 GB на последнем корректном измерении), но не установлен/не проверен.
Не заявлять готовое сравнение FP16/Q4 или полную проверку нового окна 6144.

## Инфраструктурный блокер

Docker VM в 12:21 UTC: Buffer I/O errors, EXT4 aborted journal, filesystem remounted read-only;
dockerd не запускается с `input/output error`. Это не подтверждённая синтаксическая ошибка daemon.json.
Docker DataFolder: `C:\ProgramData\DockerDesktop\vm-data`; после сбоя C: имел ~2,16 GiB свободного места.
Reset/prune/VHD/volumes не удалять. Системные настройки не менялись.
Зависшие CLI сборки и скачивания остановлены. Пользователь отложил освобождение места/перезапуск Docker.
Артефакты JSON на F: читаются; целостность SQLite в Docker volume ещё надо проверить после восстановления.

## Продолжение после явной команды

1. Прочитать checkpoint и verification; проверить Git и реальное состояние Docker, не повторять старые completed UUID.
2. Проверить дисковое пространство и Docker engine. При необходимости пользователь освобождает место/восстанавливает Docker;
   никаких factory reset и удаления томов без отдельной команды.
3. Проверить SQLite integrity и сохранённые эксперименты. JSON-копии находятся в ignored artifacts.
4. Пересобрать backend/client актуального коммита. Последняя успешная deployed image до сбоя:
   backend `cad6ec0e1f7048524b7d76e4110645cf5a8b5d88cfc3c1dfb7ecaa3cee564fb9`,
   client `e841791eb0b32c9ed7c0dbad6b33eb194f969c3e04ca181e4e1a276f790f0ccc`.
   Они имеют прежний preset 4096; рабочие файлы уже 6144. Не путать source с deployed version.
5. Выполнить итоговый Q4 specialized+skill, temperature .7, window 6144, output 512,
   top_p .8/top_k20/seed42/thinking false, holdout 10×3. Не менять prompt по h06.
6. Проверить cap64 и window1024 отдельными local negative experiments: TRUNCATED/FAILED, raw результат виден.
7. Доскачать FP16 безопасно после восстановления диска, переключить через start-day29,
   прогнать тот же frozen suite/settings. Убедиться, что в памяти одна модель и обе реально GPU.
8. DeepSeek Pro holdout 10×1 на тех же task instructions, без фиктивных cloud RAM/VRAM/context параметров.
9. Проверить UI side-by-side FP16/Q4, мобильный viewport, note/MD/reload/restart на финальной сборке.
10. Актуализировать verification, guide, осмысленно сравнить accuracy/speed/allocation; не скрывать ошибки модели.
11. Проверить секреты, git diff, при отдельной команде push. В конце вернуть выбранную пользователем активную модель.

Для итоговых прогонов использовать новые ignored-папки либо ту же квитанцию при продолжении именно старого эксперимента.
`day29-q4-holdout-final` и `day29-deepseek-holdout` пока не содержат начатых запросов.
