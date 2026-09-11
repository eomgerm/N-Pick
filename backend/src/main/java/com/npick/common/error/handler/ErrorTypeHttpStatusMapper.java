package com.npick.common.error.handler;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.npick.common.error.ErrorType;

@Component
public class ErrorTypeHttpStatusMapper {

    public HttpStatus map(ErrorType type) {
        return switch (type) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case LENGTH_REQUIRED -> HttpStatus.LENGTH_REQUIRED;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RANGE_NOT_SATISFIABLE -> HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
