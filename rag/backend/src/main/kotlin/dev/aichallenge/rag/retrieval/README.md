# Домен retrieval

`SearchService` загружает готовый индекс, проверяет embedding identity, создаёт query vector и ранжирует все сохранённые vectors через `VectorMath.cosine`. Одинаковые scores разрешаются стабильным `chunkId`; возвращается topK со source/section/text.

`VectorMath` отдельно проверяет размерность, конечность значений и ненулевые нормы. Similarity диапазона [-1,1] — геометрическая близость, не вероятность правильности ответа. Exact scan удобен для учебного корпуса; его сложность O(chunks × dimension). При росте корпуса можно вынести поиск в адаптер ANN, сохранив внешний SearchResult.

`models/SearchModels.kt` задаёт request/result DTO. `controllers/SearchController` публикует POST search конкретного index ID. В дне 21 нет rewrite, threshold refusal, генерации ответа, citation validation и памяти — эти домены добавляются последовательно.

Оба индекса ищутся отдельно. Вектора разных моделей не объединяются и не сравниваются в одном ранжировании. Изменённый runtime model digest делает поиск старого индекса недопустимым.
