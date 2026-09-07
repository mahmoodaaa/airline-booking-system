package com.project.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception thrown for bad request errors (invalid input)
 */
public class BadRequestException extends ApiBaseException {

    public BadRequestException(String message) {
        super(message, HttpStatus.BAD_REQUEST);
    }
}

