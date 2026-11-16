package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;

import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class PlaceRequests {
    static void validateId(String id) {
        if (id.isBlank() || id.length() > 80) {
            throw badRequest("유효한 ID를 보내 주세요.");
        }
    }

    static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw badRequest("limit은 1 이상 100 이하여야 합니다.");
        }
    }

    static void validateKey(String key) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            throw badRequest("Idempotency-Key는 16자 이상 128자 이하여야 합니다.");
        }
    }

    @ExceptionHandler(HomeExploreException.class)
    ResponseEntity<ErrorResponse> handle(HomeExploreException exception) {
        ErrorResponse response = ErrorResponse.of(exception.code, exception.getMessage(), List.of());
        if (exception.status == HttpStatus.SERVICE_UNAVAILABLE) {
            response = new ErrorResponse(new ErrorResponse.ErrorDetail(
                    exception.code, exception.getMessage(), List.of(), true), response.meta());
        }
        return ResponseEntity.status(exception.status).body(response);
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    ResponseEntity<ErrorResponse> handleReusedKey() {
        return handle(new HomeExploreException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "같은 멱등 키에 다른 요청을 사용할 수 없습니다."));
    }
}
