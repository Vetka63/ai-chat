package dev.aichallenge.rag.indexing.enums

/** Два независимых способа разбиения одного корпуса. */
enum class ChunkStrategy { FIXED, STRUCTURAL }

/** Состояние задания; READY означает, что все векторы уже опубликованы атомарно. */
enum class JobStatus { QUEUED, RUNNING, READY, FAILED }
