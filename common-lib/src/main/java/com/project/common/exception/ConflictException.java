package com.project.common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiBaseException {

    public ConflictException(String message) {
        super(message, HttpStatus.CONFLICT);
    }
}
