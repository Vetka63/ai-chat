package dev.aichallenge.local.web

import dev.aichallenge.local.generation.LabBusy
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Ошибки входа и занятости отображаются понятным сообщением, а не пустым ответом. */
@RestControllerAdvice
class Errors {
    @ExceptionHandler(LabBusy::class)
    fun busy(e: LabBusy) = ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("message" to e.message))
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(mapOf("message" to
        e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }))
}
