package dev.aichallenge.rag.answering.enums

/** BASELINE не обращается к индексу; RAG добавляет найденные фрагменты той же модели. */
enum class AnswerMode { BASELINE, RAG }
