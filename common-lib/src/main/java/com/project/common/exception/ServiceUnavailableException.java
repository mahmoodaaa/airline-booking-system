package com.project.common.exception;

import org.springframework.http.HttpStatus;

public class ServiceUnavailableException extends ApiBaseException {

    public ServiceUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
