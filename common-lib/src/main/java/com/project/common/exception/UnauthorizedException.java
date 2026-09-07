package com.project.common.exception;

import com.project.common.exception.ApiBaseException;
import org.springframework.http.HttpStatus;

public class UnauthorizedException extends ApiBaseException {

    public UnauthorizedException(String message) {
        super(message, HttpStatus.FORBIDDEN);
    }
}

