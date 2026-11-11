package cc.projectargus.workbench.web;

import cc.projectargus.workbench.chat.InvalidChatRequestException;
import cc.projectargus.workbench.config.JsonRequestBodySizeFilter;
import cc.projectargus.workbench.config.RequestBodyTooLargeException;
import cc.projectargus.workbench.runtime.DuplicateRequestIdException;
import cc.projectargus.workbench.runtime.InferenceCapacityExceededException;
import cc.projectargus.workbench.runtime.ModelNotReadyException;
import cc.projectargus.workbench.web.dto.ApiErrorDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralized API exception barrier converting internal exceptions into predictable, sanitized
 * {@link ApiErrorDto} JSON responses without leaking stack traces, native pointers, or filesystem paths.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidChatRequestException.class)
    public ResponseEntity<ApiErrorDto> handleInvalidChatRequest(InvalidChatRequestException ex) {
        return jsonError(HttpStatus.BAD_REQUEST, ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<ApiErrorDto> handlePayloadTooLarge(RequestBodyTooLargeException ex) {
        return jsonError(
            HttpStatus.PAYLOAD_TOO_LARGE,
            "payload_too_large",
            ex.getMessage()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorDto> handleUnreadableJson(HttpMessageNotReadableException ex) {
        RequestBodyTooLargeException tooLarge = JsonRequestBodySizeFilter.findTooLargeCause(ex);
        if (tooLarge != null) {
            return handlePayloadTooLarge(tooLarge);
        }
        return jsonError(
            HttpStatus.BAD_REQUEST,
            "malformed_json",
            "Request body must be valid JSON matching the chat API contract."
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorDto> handleValidationException(MethodArgumentNotValidException ex) {
        return jsonError(
            HttpStatus.BAD_REQUEST,
            "invalid_request",
            "Request failed validation checks."
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return jsonError(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "unsupported_media_type",
            "Content-Type must be application/json."
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorDto> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return jsonError(
            HttpStatus.METHOD_NOT_ALLOWED,
            "method_not_allowed",
            "HTTP method is not supported for this endpoint."
        );
    }

    @ExceptionHandler(DuplicateRequestIdException.class)
    public ResponseEntity<ApiErrorDto> handleDuplicateRequestId(DuplicateRequestIdException ex) {
        return jsonError(
            HttpStatus.CONFLICT,
            "duplicate_request_id",
            ex.getMessage()
        );
    }

    @ExceptionHandler(InferenceCapacityExceededException.class)
    public ResponseEntity<ApiErrorDto> handleCapacityExceeded(InferenceCapacityExceededException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        return new ResponseEntity<>(
            new ApiErrorDto("inference_busy", ex.getMessage()),
            headers,
            HttpStatus.TOO_MANY_REQUESTS
        );
    }

    @ExceptionHandler(ModelNotReadyException.class)
    public ResponseEntity<ApiErrorDto> handleModelNotReady(ModelNotReadyException ex) {
        return jsonError(
            HttpStatus.SERVICE_UNAVAILABLE,
            ex.getErrorCode(),
            ex.getMessage()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorDto> handleNotFound(NoResourceFoundException ex) {
        return jsonError(
            HttpStatus.NOT_FOUND,
            "not_found",
            "The requested endpoint or static resource does not exist."
        );
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiErrorDto> handleUnexpectedFailure(Throwable ex) {
        log.error("Unhandled internal failure while processing API request", ex);
        return jsonError(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "internal_error",
            "Local inference failed due to an unexpected internal error. Check server logs for details."
        );
    }

    private static ResponseEntity<ApiErrorDto> jsonError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiErrorDto(code, message));
    }
}
