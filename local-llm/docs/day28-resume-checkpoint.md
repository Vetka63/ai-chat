# День 28 — финальная контрольная точка

9 октября 2026, Europe/Samara. Ветка `ai-challenge-day-28` в `F:\ai-advent-challenge\ai-chat`.
Исходный checkpoint `a42a1f1`; новые доработки сохраняются отдельным итоговым коммитом.
День 29 перед переключением сохранён в своей ветке коммитом `f722d7e`. Main не менялся, push не выполнялся.

## Завершено

- Восстановлены шесть предоставленных русских рассказов Warhammer; исходные TXT не менялись.
- READY index `a15a954a-ec6e-4ddd-9809-f72bfa6877cf`: 403 чанка, dimension 1024,
  `qwen3-embedding:0.6b`, digest `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`.
- Установлены Qwen3 8B Q4_K_M и embedding. Старые Q4/FP16 дня 29 и его SQLite не удалены.
- 41 backend + 29 frontend тестов и сборки прошли. Vue 3.5.43, npm audit — 0.
- Health/disk guards, hash-навигация, HTTP rejection/receipt recovery, unavailable-model guard,
  сохранение Thinking/usage при пустом length, PARTIAL для обрезания и fenced MD.
- 30 сравнений / 60 ответов на текущем индексе: все завершены, одна подготовка контекста,
  интервалы провайдеров перекрываются во всех парах. Ответы не кешируются.
- Десять вопросов × три повтора, temperature 0, threshold 0.35, topK 5, budget 7000 символов,
  тестовый cap 512. Qwen median 1011 мс, DeepSeek 1882 мс; повторы 2–3: 904,5/1729 мс.
- Смысловое ревью: один LOCAL faith-chaplain добавил неверное руководство испытанием;
  источник S1 прямо противоречит этому. Другие повторы его не добавили. Raw не исправлялся.
- Offline: только internal сети backend/Ollama, ключ пустой, cloud false, внешний DNS отказ 6.
  Новый uncached embedding и правильный ответ Qwen; GPU проверен. Cold запуск 77 с не смешан с benchmark.
- Offline старт проверен после полной остановки контейнеров без pull, затем возвращён обычный режим.
- Через UI сохранён вывод, скачан MD, данные пережили reload/замену backend. Mobile390×844 без
  horizontal overflow, даже с раскрытыми параметрами; viewport сброшен. Console errors отсутствуют.
- Archify overview: 9/9 showcase, automated browser evidence и light/dark visual review passed,
  один ремонт подписи. SHA/размеры — `docs/diagrams/day28-diagram-receipt.md`, HTML заморожен.

## Где открыть

UI http://localhost:8483/#rag; backend8482; Ollama11436. Обычный режим: cloud опционально включён,
LOCAL не обращается к нему. Данные Docker остаются на C по решению пользователя; более 70GiB свободно.
Во время idle модель выгружается, веса не пропадают. Не применять reset/prune/down -v/перенос на F.

- Сравнение: `0e7c21a5-4825-4f50-98b4-bdfb6fdff9b3`.
- Контрпример: `d7d3e8a8-4cd5-416f-be24-856c4b38a46e`; второй повтор `e5a4abe7-9484-4d91-a545-82645b69750f`.
- Offline: `9e9be1c4-1e7b-4ae2-88b7-3516ae579471`.
- Thinking64, PARTIAL/TRUNCATED: `1bf3cf47-1a70-4c89-83b8-a63bb10c6946`.
- UNKNOWN при пороге 0.9: `d6fdfc4b-647f-4fc2-a0a9-4ac346a150f1`.
- Первый UI DeepSeek: `28cac784-c049-4475-a593-06d24b2f747a`.

35 экспериментов, RUNNING нет. 33 LOCAL / 31 оплаченная DeepSeek генерация в этом проходе;
UNKNOWN/HTTP400/replay не добавляют генераций. Новые API прогоны для просмотра не нужны.

## Документы и границы

`day28-video-guide.md`, `day28-verification.md`, `day28-architecture.md`, README домена RAG.
Схема `diagrams/day28-rag-overview.html`; прежний JSON — исторический непринятый кандидат.
Русские подписи, служебные Viewer UI/html lang — English fallback.

По просьбе пользователя используется **новый** индекс Warhammer, не буквальный Pro Git дней 21–25.
VALID/COMPLETED не доказывают истинность всех утверждений; см. контрпример и ограничения отчёта.
Raw snapshots, offline proof и screenshot — ignored `artifacts/day28-recovery-20261009`;
матрица — `artifacts/day28-restored-20261009`. Книги/env/SQLite не добавляются в Git.
Независимые SQLite-копии созданы в `artifacts/day28-recovery-20261009/backup` при остановленном backend.
Все три integrity_check — ok: RAG 6 документов/403 вектора/35 экспериментов, прежние чаты 2/8 сообщений,
день 29 — 29 экспериментов. Backend/client возвращены в рабочий обычный режим.
Фактические секреты проверены в Git-кандидатах, собранном JS, публичном DTO и MD: совпадений нет.
Гайд содержит шесть проверенных прямых ссылок, индекс READY, RUNNING нет. Локальный commit завершает проход;
push — только по новой команде пользователя. Перегенерировать матрицу для статуса не нужно.
