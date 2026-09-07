package com.project.common.exception;

import com.project.common.exception.ApiBaseException;
import org.springframework.http.HttpStatus;

public class RecordNotFoundException extends ApiBaseException {

    public RecordNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND);
    }
}
