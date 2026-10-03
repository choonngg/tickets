package com.project.ticket.global.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(errorCode.status()).body(ErrorResponse.from(errorCode));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleValidationException() {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.from(ErrorCode.INVALID_REQUEST));
    }

    public ResponseEntity<ErrorResponse> handleMissingRequestHeaderException(MissingRequestHeaderException exception) {
        if ("Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(ErrorCode.IDEMPOTENCY_KEY_REQUIRED));
        }
        return handleValidationException();
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse response;
        if (exception instanceof MissingRequestHeaderException missingHeader) {
            response = handleMissingRequestHeaderException(missingHeader).getBody();
        } else {
            response = switch (status.value()) {
                case 400 -> ErrorResponse.from(ErrorCode.INVALID_REQUEST);
                case 404 -> ErrorResponse.from(ErrorCode.RESOURCE_NOT_FOUND);
                case 405 -> ErrorResponse.from(ErrorCode.METHOD_NOT_ALLOWED);
                case 415 -> ErrorResponse.from(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
                case 500 -> ErrorResponse.from(ErrorCode.INTERNAL_SERVER_ERROR);
                default -> {
                    HttpStatus httpStatus = HttpStatus.valueOf(status.value());
                    yield new ErrorResponse(httpStatus.name(), httpStatus.getReasonPhrase());
                }
            };
        }
        return super.handleExceptionInternal(exception, response, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        log.error("Unhandled request exception", exception);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(errorCode.status()).body(ErrorResponse.from(errorCode));
    }
}
