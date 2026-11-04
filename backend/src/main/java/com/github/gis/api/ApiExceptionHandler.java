package com.github.gis.api;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.*;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    // The superclass logs exception.toString() for committed responses using this subclass's
    // application logger. Avoid that branch: exception text may contain submitted values.
    if (request instanceof ServletWebRequest servletRequest
        && servletRequest.getResponse() != null
        && servletRequest.getResponse().isCommitted()) {
      return null;
    }
    var safeHeaders = new HttpHeaders();
    safeHeaders.putAll(headers);
    safeHeaders.setContentType(MediaType.APPLICATION_JSON);
    safeHeaders.setCacheControl(CacheControl.noStore());
    return super.handleExceptionInternal(
        exception, ApiError.forStatus(status.value()), safeHeaders, status, request);
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Object> domain(ApiException exception) {
    return response(exception.status(), exception.error());
  }

  @ExceptionHandler({
    DataAccessResourceFailureException.class,
    CannotCreateTransactionException.class
  })
  public ResponseEntity<Object> unavailable(Exception exception) {
    LoggerFactory.getLogger(ApiExceptionHandler.class)
        .atWarn()
        .addKeyValue("event", "request_failed")
        .addKeyValue("reason", "database_unavailable")
        .log("request_failed");
    return response(HttpStatus.SERVICE_UNAVAILABLE, ApiError.forStatus(503));
  }

  @ExceptionHandler({QueryTimeoutException.class, TransactionTimedOutException.class})
  public ResponseEntity<Object> timedOut(Exception exception) {
    LoggerFactory.getLogger(ApiExceptionHandler.class)
        .atWarn()
        .addKeyValue("event", "request_failed")
        .addKeyValue("reason", "database_timeout")
        .log("request_failed");
    return response(HttpStatus.SERVICE_UNAVAILABLE, ApiError.forStatus(503));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpected(Exception exception) {
    // Exception text can contain SQL, connection details or submitted data.
    LoggerFactory.getLogger(ApiExceptionHandler.class)
        .atError()
        .addKeyValue("event", "request_failed")
        .addKeyValue("reason", "internal_error")
        .log("request_failed");
    return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.forStatus(500));
  }

  private ResponseEntity<Object> response(HttpStatus status, ApiError error) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .cacheControl(CacheControl.noStore())
        .body(error);
  }
}
