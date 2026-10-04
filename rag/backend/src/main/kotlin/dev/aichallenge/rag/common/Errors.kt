package dev.aichallenge.rag.common

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Ожидаемая ошибка предметной области, которую можно безопасно показать пользователю. */
class LabException(val code: String, override val message: String, val status: HttpStatus = HttpStatus.BAD_REQUEST) : RuntimeException(message)

/** Единая публичная форма ошибки API. */
data class ApiError(val code: String, val message: String)

/** Отделяет понятные ошибки ввода/инфраструктуры от непредвиденных внутренних сбоев. */
@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    @ExceptionHandler(LabException::class)
    fun domain(error: LabException): ResponseEntity<ApiError> = ResponseEntity.status(error.status).body(ApiError(error.code, error.message))
    @ExceptionHandler(NoResourceFoundException::class)
    fun missing(error: NoResourceFoundException): ResponseEntity<ApiError> = ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("route_not_found", "Эндпоинт не найден."))
    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class, IllegalArgumentException::class)
    fun invalid(error: Exception): ResponseEntity<ApiError> = ResponseEntity.badRequest().body(ApiError("invalid_request", "Проверьте параметры запроса: размер, перекрытие, стратегию и идентификаторы."))
    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled request error", error)
        return ResponseEntity.internalServerError().body(ApiError("internal_error", "Внутренняя ошибка. Подробности сохранены в логах backend."))
    }
}
