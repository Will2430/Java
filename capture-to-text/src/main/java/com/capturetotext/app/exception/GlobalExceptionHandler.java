package com.capturetotext.app.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Central place that turns exceptions thrown anywhere in the controller layer
 * into consistent JSON error bodies instead of a default 500 with a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidImageException.class)

    // we need a different status code for different exceptions
    // ResponseEntity is also some generic wrapper that represents the entire HTTP response, including its status code, headers and body
    public ResponseEntity<Map<String, Object>> handleInvalidImage(InvalidImageException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(OcrProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleOcrFailure(OcrProcessingException ex) {
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(CaptureNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(CaptureNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handlePaymentNotFound(PaymentNotFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // Bad client input on the payments API: invalid body, missing Idempotency-Key header, unparseable JSON.
    @ExceptionHandler({InvalidPaymentRequestException.class, MissingRequestHeaderException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleBadPaymentRequest(Exception ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return errorResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Map<String, Object>> handleKeyReused(IdempotencyKeyReusedException ex) {
        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(IdempotencyRequestInProgressException.class)
    public ResponseEntity<Map<String, Object>> handleInProgress(IdempotencyRequestInProgressException ex) {
        return errorResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<Map<String, Object>> handleBadSignature(InvalidWebhookSignatureException ex) {
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // The provider failed, not our server or the client: 502 Bad Gateway.
    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<Map<String, Object>> handleGateway(PaymentGatewayException ex) {
        return errorResponse(HttpStatus.BAD_GATEWAY, ex.getMessage());
    }

    // Couldn't reach the provider: the outcome is unknown, so tell the client to retry with the same key.
    // Spring picks the most specific handler, so this wins over the 502 one above for the subclass.
    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleGatewayUnavailable(PaymentGatewayUnavailableException ex) {
        return withRetryAfter(errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()), 1);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(RateLimitExceededException ex) {
        return withRetryAfter(errorResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()), ex.retryAfterSeconds());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return errorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds the maximum allowed size.");
    }

    // Spring throws this for any unmatched static resource (e.g. a missing favicon.ico);
    // it isn't an application error, so it must not fall into the 500 catch-all below.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return errorResponse(HttpStatus.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error: " + ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {

        // LinkedHashMap perserved insertion order, so the keys and value come out in the same order as they were arranged?
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        
        // handler has to build and hand back the response object itself
        return ResponseEntity.status(status).body(body);
    }

    // Retry-After tells a well-behaved client how many seconds to wait before trying again.
    private ResponseEntity<Map<String, Object>> withRetryAfter(ResponseEntity<Map<String, Object>> response,
                                                               long seconds) {
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(response.getBody());
    }
}
