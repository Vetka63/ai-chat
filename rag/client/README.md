# Vue клиент лаборатории

Обычный frontend: Vue 3 + TypeScript + Vite, не Kotlin/JS. Четыре раздела индексации дня 21 дополнены пятой вкладкой сравнения ответов дня 22. Истории чата пока нет; API key не передаётся в браузер.

- `src/main.ts`: Router, Pinia, bootstrap.
- `src/api/client.ts`: типизированные HTTP-запросы, обработка API ошибок.
- `src/api/schema.d.ts`: генерируемые типы из `../api/openapi.json`, не ручной DTO-дубликат.
- `src/features/lab/store.ts`: corpus/documents/jobs/indexes, load/refresh.
- `src/features/lab/LabView.vue`: локальное состояние tab/config/preview/modal/comparison/search, действие пользователя и polling прогресса.
- `src/features/lab/report.ts`: MD comparison и скачивание Blob; не отдельный backend exporter.
- `src/features/answering/AnswerLab.vue`: отдельная форма двух режимов, независимые результаты/ошибки, просмотр snapshot источника и фактического промпта.
- `src/features/answering/report.ts`: MD сравнения с полями ручного вывода; `answering.css` — адаптивное оформление.
- `src/style.css`: адаптивная раскладка и локальные системные шрифты, без внешнего font CDN.

UI опрашивает jobs/indexes раз в 2 секунды, не создаёт embedding jobs сам. На unmount timer снимается; повторные concurrent refresh запрещены. Данные сохраняются сервером, browser state не является индексом.

Сравнение ответов вынесено в отдельную feature-директорию. При развитии дней 23–25 добавятся retrieval settings, citations и chat. Не нужно превращать LabView в весь агентский монолит или переносить старые UI-зависимости.

`npm run build` генерирует типы и проверяет TypeScript до Vite build. `npm test` — unit. `npm run test:e2e` — браузерные проверки живого приложения; переменная `RAG_UI_URL` позволяет указать другой адрес. Node >=22.12.

Обычные тесты генерации подменяют ответы API и не оплачиваются. `RAG_LIVE=true` включает отдельный тест `@live`: он делает два реальных платных вызова DeepSeek. Пользовательское сравнение тоже оплачивает два вызова; автоматических повторов нет. Ответы не исполняются как HTML. Результаты сохраняются при смене вкладки, но перед reload их нужно скачать в MD/JSON.
