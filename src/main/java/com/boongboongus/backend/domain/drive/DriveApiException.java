package com.boongboongus.backend.domain.drive;

import org.springframework.http.HttpStatus;

final class DriveApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    DriveApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
