package com.github.gis.api;

import org.springframework.http.HttpStatus;

/** Explicit public errors only; never construct these from SQL/exception messages. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiError error;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.error = new ApiError(code, message);
    }

    public HttpStatus status() { return status; }
    public ApiError error() { return error; }
}
