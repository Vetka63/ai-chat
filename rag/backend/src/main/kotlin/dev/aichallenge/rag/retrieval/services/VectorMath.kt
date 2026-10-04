package dev.aichallenge.rag.retrieval.services

import kotlin.math.sqrt

/** Точный cosine search малого локального корпуса, без native FAISS и без приближённого ANN. */
object VectorMath {
    fun validate(vector: FloatArray) {
        require(vector.isNotEmpty() && vector.all { it.isFinite() } && vector.any { it != 0f }) { "Embedding должен быть конечным, непустым и ненулевым." }
    }
    fun cosine(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        validate(a); validate(b)
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        a.indices.forEach { i -> dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return (dot / sqrt(aa * bb)).coerceIn(-1.0, 1.0)
    }
}
