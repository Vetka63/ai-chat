package dev.aichallenge.local.web

import dev.aichallenge.local.generation.LabBusy
import dev.aichallenge.local.generation.InvalidModel
import dev.aichallenge.local.chat.ChatConflict
import dev.aichallenge.local.chat.ChatMissing
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Ошибки входа и занятости отображаются понятным сообщением, а не пустым ответом. */
@RestControllerAdvice
class Errors {
    @ExceptionHandler(dev.aichallenge.local.rag.models.RagFailure::class)
    fun rag(e: dev.aichallenge.local.rag.models.RagFailure) = ResponseEntity.badRequest().body(mapOf("message" to e.message))
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException::class)
    fun upload(e: org.springframework.web.multipart.MaxUploadSizeExceededException) = ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(mapOf("message" to "Файл слишком большой. Максимум 5 МБ."))
    @ExceptionHandler(LabBusy::class)
    fun busy(e: LabBusy) = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to e.message))
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
