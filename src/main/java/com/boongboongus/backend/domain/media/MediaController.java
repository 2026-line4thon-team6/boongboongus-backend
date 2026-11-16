package com.boongboongus.backend.domain.media;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.media.MediaService.MediaError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/media")
@Tag(name = "사진 업로드")
class MediaController {
    private final MediaService media;

    MediaController(MediaService media) {
        this.media = media;
    }

    @PostMapping("/uploads")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "createMediaUpload", summary = "카드 사진 업로드 URL 발급")
    ResponseEntity<ApiResponse<MediaService.UploadTicket>> create(Principal principal,
            @RequestHeader("X-Couple-Id") String couple, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody UploadRequest request) {
        validateHeader(couple, 1, 80, "X-Couple-Id");
        validateHeader(key, 16, 128, "Idempotency-Key");
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(media.create(principal, couple, key, request)));
    }

    @PostMapping("/{mediaId}/complete")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "completeMediaUpload", summary = "카드 사진 업로드 완료 검증")
    ApiResponse<MediaService.Media> complete(Principal principal, @PathVariable String mediaId,
            @RequestHeader("X-Couple-Id") String couple, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CompleteRequest request) {
        validateHeader(couple, 1, 80, "X-Couple-Id");
        validateHeader(key, 16, 128, "Idempotency-Key");
        return ApiResponse.success(media.complete(principal, couple, mediaId, key, request.sha256()));
    }

    @GetMapping("/{mediaId}/download-url")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "getMediaDownloadUrl", summary = "카드 사진 임시 조회 URL 발급")
    ResponseEntity<ApiResponse<MediaService.DownloadUrl>> downloadUrl(Principal principal, @PathVariable String mediaId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(media.downloadUrl(principal, mediaId)));
    }

    @DeleteMapping("/{mediaId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(operationId = "deleteTemporaryMedia", summary = "미첨부 카드 사진 폐기")
    ResponseEntity<Void> delete(Principal principal, @PathVariable String mediaId,
            @RequestHeader("X-Couple-Id") String couple) {
        validateHeader(couple, 1, 80, "X-Couple-Id");
        media.delete(principal, couple, mediaId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{mediaId}/content")
    @Operation(operationId = "putMediaContent", summary = "발급된 URL에 카드 사진 원본 전송")
    ResponseEntity<Void> put(@PathVariable String mediaId, @RequestParam(required = false) String token,
            HttpServletRequest request) throws IOException {
        media.put(mediaId, token, request.getContentType(), request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/{mediaId}/content")
    @Operation(operationId = "getMediaContent", summary = "발급된 URL에서 카드 사진 조회")
    ResponseEntity<byte[]> get(@PathVariable String mediaId, @RequestParam(required = false) String token) {
        var photo = media.download(mediaId, token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer").body(photo.bytes());
    }

    @ExceptionHandler(MediaError.class)
    ResponseEntity<ErrorResponse> error(MediaError error) {
        ErrorResponse body = ErrorResponse.of(error.code, error.getMessage(), List.of());
        if (error.status == HttpStatus.SERVICE_UNAVAILABLE || error.status == HttpStatus.TOO_MANY_REQUESTS) {
            body = new ErrorResponse(new ErrorResponse.ErrorDetail(error.code, error.getMessage(), List.of(), true),
                    body.meta());
        }
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).body(body);
    }

    private static void validateHeader(String value, int min, int max, String name) {
        if (value.isBlank() || value.length() < min || value.length() > max) {
            throw new MediaError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", name + " 값을 확인해 주세요.");
        }
    }

    record UploadRequest(@NotBlank @Size(max = 200) String fileName, @NotBlank String contentType,
            @NotNull @Min(1) Long bytes, @NotNull Purpose purpose) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown media upload field");
        }
    }

    record CompleteRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown media completion field");
        }
    }

    enum Purpose {
        CARD_PHOTO
    }
}
