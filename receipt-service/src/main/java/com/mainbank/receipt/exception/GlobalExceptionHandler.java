package com.mainbank.receipt.exception;

import com.mainbank.receipt.storage.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps failures to RFC 7807 problem responses with a stable {@code code} field, like the ledger service. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ReceiptException.class)
    ResponseEntity<ProblemDetail> receipt(ReceiptException e) {
        HttpStatus status = switch (e.code()) {
            case RECEIPT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RECEIPT_NOT_READY -> HttpStatus.CONFLICT;
        };
        return problem(status, e.code().name(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> badType(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '" + e.getName() + "' has an invalid value");
    }

    @ExceptionHandler(MinioException.class)
    ResponseEntity<ProblemDetail> storage(MinioException e) {
        log.warn("Object storage failure: {}", e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE", "Receipt storage is temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setProperty("code", code);
        return ResponseEntity.status(status).body(pd);
    }
}
