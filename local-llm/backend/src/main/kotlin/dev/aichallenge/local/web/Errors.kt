package dev.aichallenge.local.web

import dev.aichallenge.local.generation.LabBusy
import dev.aichallenge.local.generation.InvalidModel
import dev.aichallenge.local.chat.ChatConflict
import dev.aichallenge.local.chat.ChatMissing
import dev.aichallenge.local.privateaccess.ContextRejected
import dev.aichallenge.local.privateaccess.RateLimited
import dev.aichallenge.local.privateaccess.PrivateProperties
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Ошибки входа и занятости отображаются понятным сообщением, а не пустым ответом. */
@RestControllerAdvice
class Errors(private val config: PrivateProperties) {
    @ExceptionHandler(LabBusy::class)
    fun busy(e: LabBusy) = ResponseEntity.status(if (config.enabled) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.CONFLICT)
        .header("Retry-After", "5").body(mapOf("message" to e.message))
    @ExceptionHandler(RateLimited::class)
    fun rate(e: RateLimited) = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header("Retry-After", e.retryAfter.toString()).body(mapOf("message" to e.message))
    @ExceptionHandler(ContextRejected::class)
    fun context(e: ContextRejected) = ResponseEntity.status(422).body(mapOf("message" to e.message))
    @ExceptionHandler(ChatConflict::class)
    fun conflict(e: ChatConflict) = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to e.message))
    @ExceptionHandler(ChatMissing::class)
    fun missing(e: ChatMissing) = ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("message" to e.message))
    @ExceptionHandler(InvalidModel::class)
    fun model(e: InvalidModel) = ResponseEntity.badRequest().body(mapOf("message" to e.message))
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(mapOf("message" to
        e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }))
}
