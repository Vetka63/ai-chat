package dev.aichallenge.rag.documents.models

/** Сохранённое происхождение одного файла русской Pro Git. */
data class SourceFile(val path: String, val sha256: String)

/** Manifest закрепляет commit, лицензию, выбор глав и оригинальные контрольные суммы. */
data class CorpusManifest(
    val corpusId: String, val repository: String, val revision: String, val archiveSha256: String,
    val license: String, val attribution: String, val selectedChapters: List<String>, val files: List<SourceFile>,
    val resources: List<SourceFile> = emptyList(),
)

/** Раздел документа с диапазоном в каноническом тексте; endExclusive не включает последний символ. */
data class DocumentSection(val title: String, val start: Int, val endExclusive: Int)

/** Смысловой блок: абзац, заголовок или пример команд с исходным номером строки. */
data class TextBlock(val kind: String, val start: Int, val endExclusive: Int, val sourceLine: Int)

/** Нормализованный файл; диапазоны используются и для coverage, и для будущих цитат. */
data class Document(
    val id: String, val source: String, val sourceUrl: String, val title: String, val chapter: String,
    val text: String, val sha256: String, val sections: List<DocumentSection>, val blocks: List<TextBlock>,
)

/** Краткое представление документа без большого текста для списка UI. */
data class DocumentInfo(val id: String, val title: String, val chapter: String, val source: String, val characters: Int, val words: Int, val sections: Int)

/** Один неизменяемый снимок корпуса и проверенная версия нормализатора. */
data class CorpusSnapshot(val manifest: CorpusManifest, val documents: List<Document>, val snapshotId: String, val parserVersion: String = "asciidoc-safe-v2")

/** Показатели объёма по уникальному исходному тексту, без дубликатов overlap. */
data class CorpusInfo(val snapshotId: String, val manifest: CorpusManifest, val documentCount: Int, val sectionCount: Int, val characters: Int, val words: Int, val estimatedPages: Double, val pageFormula: String)
