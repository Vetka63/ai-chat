package dev.aichallenge.rag.taskmemory.enums

/** Только память задачи текущего чата; эти слои не являются источниками знаний о Git. */
enum class MemoryLayer { GOAL, CLARIFICATIONS, CONSTRAINTS, TERMS }
