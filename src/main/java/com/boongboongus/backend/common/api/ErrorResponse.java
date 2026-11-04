package com.boongboongus.backend.common.api;

import java.util.List;

public record ErrorResponse(ErrorDetail error, ApiResponse.Meta meta) {

    public static ErrorResponse of(String code, String message, List<FieldError> details) {
        return new ErrorResponse(new ErrorDetail(code, message, details, false), ApiResponse.Meta.current());
    }

    public record ErrorDetail(String code, String message, List<FieldError> details, boolean retryable) {
    }

    public record FieldError(String field, String reason) {
    }
}
