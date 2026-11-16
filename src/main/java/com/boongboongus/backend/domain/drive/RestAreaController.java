package com.boongboongus.backend.domain.drive;

import static com.boongboongus.backend.domain.drive.DrivePreparationController.validateIdentifier;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "휴게소·스탬프")
class RestAreaController {
    private final DrivePreparationService drives;
    private final RestAreaService areas;
    private final StampService stamps;

    RestAreaController(DrivePreparationService drives, RestAreaService areas, StampService stamps) {
        this.drives = drives;
        this.areas = areas;
        this.stamps = stamps;
    }

    @GetMapping("/drives/{driveId}/rest-areas")
    @Operation(operationId = "getDriveRestAreas", summary = "주행 경로와 경로 내 휴게소")
    ApiResponse<RestAreaService.RouteAreas> alongDrive(Principal principal, @PathVariable String driveId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        validateIdentifier(driveId, 1, 80, "driveId");
        var actor = drives.currentActor(principal);
        return ApiResponse.success(areas.alongDrive(actor.userId(), actor.couple().id(), driveId, cursor, limit));
    }

    @GetMapping("/rest-areas")
    @Operation(operationId = "searchRestAreas", summary = "휴게소 검색")
    ApiResponse<RestAreaPage> search(Principal principal, @RequestParam(required = false) String q,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        String coupleId = drives.optionalCoupleId(principal);
        Coordinate location = null;
        if (latitude != null || longitude != null) {
            if (latitude == null || longitude == null || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new DriveApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "위도와 경도를 함께 확인해 주세요.");
            }
            location = new Coordinate(latitude, longitude);
        }
        return ApiResponse.success(areas.search(coupleId, q, location, cursor, limit));
    }

    @GetMapping("/rest-areas/{restAreaId}")
    @Operation(operationId = "getRestArea", summary = "휴게소 상세")
    ApiResponse<RestAreaService.Detail> detail(Principal principal, @PathVariable String restAreaId) {
        validateIdentifier(restAreaId, 1, 80, "restAreaId");
        return ApiResponse.success(areas.detail(drives.optionalCoupleId(principal), restAreaId));
    }

    @PostMapping("/drives/{driveId}/stamp-claims")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "claimStamp", summary = "휴게소 방문 검증 요청")
    ApiResponse<StampService.ClaimView> claim(Principal principal, @PathVariable String driveId,
            @RequestHeader("X-Couple-Id") String coupleId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ClaimRequest request) {
        DrivePreparationController.validateControlHeaders(driveId, coupleId, key);
        var actor = drives.currentActor(principal);
        drives.requireMatchingCouple(actor, coupleId);
        return ApiResponse.success(stamps.claim(actor.userId(), coupleId, driveId, key, request));
    }

    @GetMapping("/stamp-claims/{claimId}")
    @Operation(operationId = "getStampClaim", summary = "스탬프 요청 상태 조회")
    ApiResponse<StampService.ClaimView> claimResult(Principal principal, @PathVariable String claimId) {
        validateIdentifier(claimId, 1, 80, "claimId");
        var actor = drives.currentActor(principal);
        return ApiResponse.success(stamps.get(actor.couple().id(), claimId));
    }

    @GetMapping("/couples/me/stamps")
    @Operation(operationId = "getStampBook", summary = "커플 도장판 조회")
    ApiResponse<StampService.StampBook> book(Principal principal, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        var actor = drives.currentActor(principal);
        return ApiResponse.success(stamps.book(actor.couple().id(), cursor, limit));
    }

    @ExceptionHandler(DriveApiException.class)
    ResponseEntity<ErrorResponse> handleError(DriveApiException error) {
        return DrivePreparationController.driveErrorResponse(error);
    }

    record ClaimRequest(@NotBlank @Size(max = 80) String restAreaId, @NotNull Method method,
            @Valid DriveControlRequest.InitialCoordinate coordinate, Instant observedAt) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown stamp claim field");
        }
    }

    enum Method {
        GPS, MANUAL
    }
}