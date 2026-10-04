package dev.aichallenge.rag.common

import java.security.MessageDigest

/** Создаёт воспроизводимые SHA256 для корпуса, нормализованного текста и чанков. */
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Хеширует текст строго в UTF-8, независимо от кодировки Windows. */
fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
