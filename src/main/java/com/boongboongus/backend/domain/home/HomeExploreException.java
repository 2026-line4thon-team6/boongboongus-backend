package com.boongboongus.backend.domain.home;

import org.springframework.http.HttpStatus;

final class HomeExploreException extends RuntimeException {
    final HttpStatus status;
    final String code;

    HomeExploreException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static HomeExploreException badRequest(String message) {
        return new HomeExploreException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }
}
