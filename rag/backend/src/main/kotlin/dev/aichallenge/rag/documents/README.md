# Домен documents

Отвечает за происхождение, целостность и нормализацию корпуса. Не строит embeddings и не вызывает LLM.

- `models/Documents.kt`: manifest, source hashes, document, section/block offsets, snapshot и компактная информация для UI.
- `ports/DocumentLoader`: контракт преобразования одного допустимого исходника в документ.
- `adapters/AsciiDocDocumentLoader`: поддерживаемый поднабор AsciiDoc; сохраняет code blocks, снимает техническую разметку, не исполняет команды, отвергает include.
- `services/CorpusService`: проверяет whitelist путей и SHA файлов, один раз создаёт snapshot; его ID зависит от revision, нормализованных текстов и версии parser.
- `controllers/DocumentController`: corpus info, список документов и полный текст выбранного документа.

Рабочий snapshot immutable на время жизни backend. Новый revision/изменение parser требует перезапуска и нового индекса. Старые опубликованные snapshots сохранены в SQLite и доступны по индексу, чтобы будущие цитаты не ссылались на изменённый текст.

Нормализация — не полный AsciiDoc renderer. CorpusService обрабатывает только manifest-whitelisted include: самостоятельный section становится ссылкой на отдельно индексируемый документ; Ruby resource разворачивается в code block после проверки SHA. Source line metadata отражает начальные строки блоков подготовленного входа, включая развёрнутый include; точный диапазон чанка находится в нормализованном `Document.text`. Corpus whitespace и code не заменяются готовыми QA-парами.
