package com.electrahub.aisupport.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class TenantAiAccessException extends RuntimeException {
    public TenantAiAccessException(String message) {
        super(message);
    }
}
