# Алгоритм индексации по классам

Этот гайд помогает пройти от кнопки на UI до записанного embedding-вектора. В день 21 модель не генерирует текстовый ответ: приложение подготавливает базу для будущего RAG.

В текущей ветке дня 22 генерация уже добавлена отдельно от этого процесса. Путь от вопроса до ответа DeepSeek по классам и режимам описан в [day22-guide.md](day22-guide.md). Этот документ остаётся гайдом по индексации.

## Открытие корпуса

Vue LabView загружается через Router. Pinia store вызывает API client и параллельно получает corpus, documents, indexes и jobs. Kotlin DocumentController делегирует CorpusService.

CorpusService читает manifest по настроенному corpus path, проверяет разрешённые относительные пути и SHA256 исходников/resources. Сначала разрешает include только по этому whitelist: Ruby-пример вставляется как текст code block, самостоятельный subtree section становится ссылкой на отдельный документ без дублирования. AsciiDocDocumentLoader получает каждый подготовленный текстовый файл и создаёт Document с canonical text, sections и blocks. Один CorpusSnapshot закрепляет результаты и parser version; последующие запросы не скачивают книгу снова. Никакой Ruby/Git-код из книги не исполняется.

DocumentInfo — маленькая форма для списка. Полный Document запрашивается только при открытии окна источника. Поэтому UI не загружает всю книгу в каждую карточку.

## Preview без модели

Кнопка «Посмотреть чанки» вызывает POST `/api/v1/preview` с ChunkConfig. IndexController вызывает IndexingService.preview, затем ChunkingService.

ChunkingService проверяет настройки и выбирает алгоритм по enum через реестр ChunkingStrategy. FixedSizeChunking и StructuralChunking возвращают диапазоны исходного текста. Сервис создаёт чанки через точный substring, добавляет provenance и вычисляет ID.

Coverage измеряется BitSet объединением диапазонов каждого документа. Например, два окна `[0,1000)` и `[900,1900)` дают 1900 уникальных символов, а не 2000. Метрики размера считают оба окна, coverage и объём корпуса — только уникальный текст.

Preview возвращает chunks и ChunkMetrics. Он не сохраняет вектора и не создаёт готовый индекс. Даже при остановленной Ollama этот экран работает.

## Построение индекса

Кнопка «Построить индекс» вызывает POST jobs. Backend сначала рассчитывает preview; если другое задание уже выполняется, возвращает indexing_busy. Затем создаётся IndexJob в SQLite и HTTP отвечает 202.

Executor начинает build отдельно от HTTP потока. Через EmbeddingProvider.identity он проверяет модель в `/api/tags`, digest и размерность диагностическим embed. Далее каждая группа из batchSize чанков отправляется в Ollama `/api/embed`.

OllamaEmbeddingProvider сериализует input, выставляет `truncate=false`, контролирует timeout и HTTP status. Он проверяет число векторов и координаты. VectorMath.validate отвергает NaN, Infinity, пустые и нулевые вектора. Ошибка становится FAILED job; пустого «успешного индекса» не будет.

После batch backend обновляет processed/total в jobs. Vue периодически получает новое состояние. Обновление браузера не стирает серверный прогресс.

После всех batches проверяется embedding identity ещё раз. SqliteIndexRepository.publish в одной transaction записывает snapshot, IndexInfo, chunks, vector BLOB и READY job. Вектора сериализуются little-endian по четыре байта на координату.

## Ошибка и restart

Готовые индексы не перезаписываются новыми jobs. При ошибке вычисления они остаются доступными. При SQL-сбое публикация откатывается целиком.

После restart repository переводит старые QUEUED/RUNNING jobs в FAILED и объясняет, что нужно повторить построение. READY indexes остаются. Готовый индекс не зависит от содержимого оперативной памяти предыдущего процесса.

## Диагностический поиск

SearchController получает вопрос и index ID. SearchService проверяет совместимость embedding runtime, вычисляет вектор вопроса и читает сохранённые vectors. VectorMath.cosine считает скалярное произведение, делённое на произведение длин векторов. Поиск точный, перебирает все чанки.

Например, запрос «как убрать последний коммит, но оставить изменения» должен привести к тексту о reset/отмене. Backend возвращает пять SearchHit с rank, similarity и полными chunks. Модель не выбирает инструмент и не пишет ответ: orchestration здесь детерминированное.

## Сравнение и отчёт

IndexingService.compare проверяет corpus snapshot и embedding identity. UI показывает сохранённые метрики рядом и предупреждает, если конфигурации отличаются. `comparisonMarkdown` собирает отчёт на клиенте из этих измерений и, при наличии, текущих результатов диагностического поиска.

Экспорт не запускает новое индексирование и не отправляет данные внешним сервисам. «Больше cosine» само по себе не делает стратегию победителем: оцениваем также попадание в ожидаемый раздел и сохранность текста.

## Куда вносить дальнейшие изменения

Новый формат документов — documents/ports и documents/adapters. Другой chunking — реализация ChunkingStrategy и новое значение enum. Иная embedding-модель/runtime — EmbeddingProvider adapter с отдельной identity. Хранилище — IndexRepository adapter. Генеративные ответы уже добавлены в отдельном домене answering дня 22, не в контроллере chunking.
