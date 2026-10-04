# Vue клиент лаборатории

Обычный frontend: Vue 3 + TypeScript + Vite, не Kotlin/JS. Четыре раздела индексации дня 21 дополнены сравнением ответов дня 22, вкладкой «Фильтр и rewrite» дня 23 и «Источники и цитаты» дня 24. Истории чата пока нет; API key не передаётся в браузер.

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
- `src/style.css`: адаптивная раскладка и локальные системные шрифты, без внешнего font CDN.

UI опрашивает jobs/indexes раз в 2 секунды, не создаёт embedding jobs сам. На unmount timer снимается; повторные concurrent refresh запрещены. Данные сохраняются сервером, browser state не является индексом.

Сравнение ответов, retrieval-эксперименты и grounded-ответы вынесены в отдельные feature-директории. День 25 добавит chat. Не нужно превращать LabView в весь агентский монолит или переносить старые UI-зависимости.

`npm run build` генерирует типы и проверяет TypeScript до Vite build. `npm test` — unit. `npm run test:e2e` — браузерные проверки живого приложения; переменная `RAG_UI_URL` позволяет указать другой адрес. Node >=22.12.

Обычные тесты генерации подменяют ответы API и не оплачиваются. `RAG_LIVE=true` включает три теста `@live`: день 22 делает два платных вызова, день 23 — до пяти (один rewrite + четыре ответа), день 24 — один. По умолчанию UI дня 23 сравнивает три режима: до четырёх вызовов. «Только поиск» без rewrite бесплатен для LLM; с rewrite делает один платный вызов. В дне 24 threshold gate без rewrite пропускает LLM; непустой контекст делает один JSON-вызов. Автоматических повторов нет. Ответы не исполняются как HTML. Результаты сохраняются при смене вкладки, но перед reload их нужно скачать в MD/JSON.
