package com.project.common.exception;

import org.springframework.http.HttpStatus;

public abstract class ApiBaseException extends RuntimeException {

    private final HttpStatus statusCode;

    public ApiBaseException(String message, HttpStatus statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public HttpStatus getStatusCode() {
        return statusCode;
    }
}

