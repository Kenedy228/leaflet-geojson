package com.github.gis.api;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setContentType(MediaType.APPLICATION_JSON);
        safeHeaders.setCacheControl(CacheControl.noStore());
        return super.handleExceptionInternal(exception, ApiError.forStatus(status.value()), safeHeaders, status, request);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> domain(ApiException exception) {
        return response(exception.status(), exception.error());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<Object> unavailable(DataAccessResourceFailureException exception) {
        logger.error("API resource unavailable: " + exception.getClass().getSimpleName());
        return response(HttpStatus.SERVICE_UNAVAILABLE, ApiError.forStatus(503));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception exception) {
        // Exception text can contain SQL, connection details or submitted data.
        logger.error("API failure: " + exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.forStatus(500));
    }

    private ResponseEntity<Object> response(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore()).body(error);
    }
}
