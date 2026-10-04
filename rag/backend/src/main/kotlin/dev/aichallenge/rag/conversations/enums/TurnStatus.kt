package dev.aichallenge.rag.conversations.enums

/** PENDING записан до сети; INTERRUPTED обозначает рестарт, а не потерянный user turn. */
enum class TurnStatus { PENDING, COMPLETED, INTERRUPTED }
