package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/drives")
@Tag(name = "주행·GPS")
public class DrivePreparationController {
    private final DrivePreparationService service;

    DrivePreparationController(DrivePreparationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(operationId = "prepareDrive", summary = "드라이브 준비 세션 생성")
    public ResponseEntity<ApiResponse<DriveView>> prepare(
            Principal principal,
            @RequestHeader("X-Couple-Id") String coupleId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DrivePreparationRequest request) {
        validateIdentifier(coupleId, 1, 80, "X-Couple-Id");
        validateIdentifier(key, 16, 128, "Idempotency-Key");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(service.prepare(principal, coupleId, key, request)));
    }

    @GetMapping("/active")
    @Operation(operationId = "getActiveDrive", summary = "현재 드라이브 조회")
    public ApiResponse<DriveView.Current> active(Principal principal) {
        return ApiResponse.success(new DriveView.Current(service.active(principal)));
    }

    @PostMapping("/{driveId}/cancel")
    @Operation(operationId = "cancelPreparedDrive", summary = "준비 세션 취소")
    public ApiResponse<DriveView> cancel(
            Principal principal,
            @PathVariable String driveId,
            @RequestHeader("X-Couple-Id") String coupleId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody DrivePreparationRequest.Cancel request) {
        validateIdentifier(driveId, 1, 80, "driveId");
        validateIdentifier(coupleId, 1, 80, "X-Couple-Id");
        validateIdentifier(key, 16, 128, "Idempotency-Key");
        return ApiResponse.success(service.cancel(principal, coupleId, key, driveId, request));
    }

    private static void validateIdentifier(String value, int min, int max, String field) {
        if (value.isBlank() || value.length() < min || value.length() > max) {
            throw new DriveApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", field + " 값을 확인해 주세요.");
        }
    }

    @ExceptionHandler(DriveApiException.class)
    ResponseEntity<ErrorResponse> handleDriveError(DriveApiException error) {
        ErrorResponse body = ErrorResponse.of(error.code(), error.getMessage(), List.of());
        if (error.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            body = new ErrorResponse(new ErrorResponse.ErrorDetail(
                    error.code(), error.getMessage(), List.of(), true), body.meta());
        }
        return ResponseEntity.status(error.status()).body(body);
    }
}
