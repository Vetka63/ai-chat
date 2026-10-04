# Vue клиент лаборатории

Обычный frontend: Vue 3 + TypeScript + Vite, не Kotlin/JS. В дне 21 один экран лаборатории с четырьмя разделами, без чата и без API key.

- `src/main.ts`: Router, Pinia, bootstrap.
- `src/api/client.ts`: типизированные HTTP-запросы, обработка API ошибок.
- `src/api/schema.d.ts`: генерируемые типы из `../api/openapi.json`, не ручной DTO-дубликат.
- `src/features/lab/store.ts`: corpus/documents/jobs/indexes, load/refresh.
- `src/features/lab/LabView.vue`: локальное состояние tab/config/preview/modal/comparison/search, действие пользователя и polling прогресса.
- `src/features/lab/report.ts`: MD comparison и скачивание Blob; не отдельный backend exporter.
- `src/style.css`: адаптивная раскладка и локальные системные шрифты, без внешнего font CDN.

UI опрашивает jobs/indexes раз в 2 секунды, не создаёт embedding jobs сам. На unmount timer снимается; повторные concurrent refresh запрещены. Данные сохраняются сервером, browser state не является индексом.

При развитии дней 22–25 добавятся feature-директории для answer comparison, retrieval settings, citations и chat. Не нужно превращать LabView в весь агентский монолит или переносить старые UI-зависимости.

`npm run build` генерирует типы и проверяет TypeScript до Vite build. `npm test` — unit. `npm run test:e2e` — браузерные проверки живого приложения; переменная `RAG_UI_URL` позволяет указать другой адрес. Node >=22.12.
