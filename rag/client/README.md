# Vue клиент лаборатории

Обычный frontend: Vue 3 + TypeScript + Vite, не Kotlin/JS. Лаборатории дней 21–24 дополнены вкладкой «Чат с RAG и памятью» дня 25. История чата хранится на backend, API key не передаётся в браузер.

- `src/main.ts`: Router, Pinia, bootstrap.
- `src/api/client.ts`: типизированные HTTP-запросы, обработка API ошибок.
- `src/api/schema.d.ts`: генерируемые типы из `../api/openapi.json`, не ручной DTO-дубликат.
- `src/features/lab/store.ts`: corpus/documents/jobs/indexes, load/refresh.
- `src/features/lab/LabView.vue`: локальное состояние tab/config/preview/modal/comparison/search, действие пользователя и polling прогресса.
- `src/features/lab/report.ts`: MD comparison и скачивание Blob; не отдельный backend exporter.
- `src/features/answering/AnswerLab.vue`: отдельная форма двух режимов, независимые результаты/ошибки, просмотр snapshot источника и фактического промпта.
- `src/features/answering/report.ts`: MD сравнения с полями ручного вывода; `answering.css` — адаптивное оформление.
- `src/features/experiments/ExperimentLab.vue`: параметры четырёх режимов, единый API batch, shared rewrite, независимые карточки и журнал кандидатов.
- `src/features/experiments/report.ts`: экспорт фактического snapshot сравнения в MD; стоимость rewrite не размножается по колонкам.
- `src/features/grounding/GroundingLab.vue`: отдельный вопрос с проверяемыми цитатами, index-scoped документ с подсветкой, quarantined raw JSON, unknown/invalid/error отдельно.
- `src/features/grounding/report.ts`: MD только публичного ответа; JSON содержит также явно непроверенный diagnostic trace.
- `src/features/conversations/ConversationLab.vue`: список чатов, creation settings, диалог с auto-scroll, память с provenance, цитаты и экспорт; pending recovery через GET polling.
- `src/features/conversations/report.ts`: MD истории, проверенных ответов и памяти, без raw JSON модели.
- `src/style.css`: адаптивная раскладка и локальные системные шрифты, без внешнего font CDN.

UI опрашивает jobs/indexes раз в 2 секунды, не создаёт embedding jobs сам. На unmount timer снимается; повторные concurrent refresh запрещены. Данные сохраняются сервером, browser state не является индексом.

Сравнения, grounded-ответы и сохраняемые чаты вынесены в отдельные feature-директории. LabView переключает вкладки, не реализует весь агентский флоу. История и память восстанавливаются с API. LocalStorage хранит выбранный ID, черновики и receipt незавершённых отправок по чатам, но не заменяет серверную историю.

`npm run build` генерирует типы и проверяет TypeScript до Vite build. `npm test` — unit. `npm run test:e2e` — браузерные проверки живого приложения; переменная `RAG_UI_URL` позволяет указать другой адрес. Node >=22.12.

Обычные тесты генерации подменяют ответы API и не оплачиваются. `RAG_LIVE=true` включает четыре `@live` теста: день 22 — два вызова, день 23 — до пяти, день 24 — до четырёх, день 25 — до десяти за два обмена. Чат добавляет preparation к grounding: до пяти стадий за turn с одной правкой и повторной проверкой; пустой retrieval не отменяет уже выполненную подготовку. HTTP retry нет. Live-тесты дней 24–25 сохраняют JSON на диск в client/test-results до assertions: один in-memory attachment list-reporter не сохраняет. Каталог тест-раннера перезаписывается следующим запуском, поэтому доказательства следует скопировать в отдельный каталог data. Ответы не исполняются как HTML. Лабораторные результаты надо скачать перед reload, сохраняемый чат восстанавливается автоматически.
