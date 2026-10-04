package dev.aichallenge.rag.rewriting.ports

import dev.aichallenge.rag.rewriting.models.RewriteTrace

/** Заменяемый способ переформулирования вопроса только для retrieval. */
interface QueryRewriter {
    fun rewrite(question: String): RewriteTrace
}
