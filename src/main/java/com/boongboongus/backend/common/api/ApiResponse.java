package com.boongboongus.backend.common.api;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;

public record ApiResponse<T>(T data, Meta meta) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, Meta.current());
    }

    public record Meta(String requestId, Instant serverTime) {
        public static final String REQUEST_ID_KEY = "requestId";

        public static Meta current() {
            String requestId = MDC.get(REQUEST_ID_KEY);
            return new Meta(requestId == null ? UUID.randomUUID().toString() : requestId, Instant.now());
        }
    }
}
