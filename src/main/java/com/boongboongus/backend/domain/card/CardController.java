package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.domain.card.CardService.CardError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "카드·기록")
@SecurityRequirement(name = "bearerAuth")
class CardController {
    private final CardService cards;

    CardController(CardService cards) {
        this.cards = cards;
    }

    @GetMapping("/drives/{driveId}/card")
    @Operation(operationId = "getDriveCard")
    ApiResponse<CardView> driveCard(Principal principal, @PathVariable String driveId) {
        return ApiResponse.success(cards.get(principal, driveId, true));
    }

    @GetMapping("/cards/{cardId}")
    @Operation(operationId = "getCard")
    ApiResponse<CardView> card(Principal principal, @PathVariable String cardId) {
        return ApiResponse.success(cards.get(principal, cardId, false));
    }

    @PatchMapping("/cards/{cardId}/draft")
    @Operation(operationId = "saveCardDraft")
    ApiResponse<CardView> draft(Principal principal, @PathVariable String cardId,
            @RequestHeader("X-Couple-Id") String coupleId, @Valid @RequestBody Draft request) {
        validateHeader(coupleId, 1, 80);
        return ApiResponse.success(cards.draft(principal, coupleId, cardId, request));
    }

    @PutMapping("/cards/{cardId}/reflections/me")
    @Operation(operationId = "saveMyReflection")
    ApiResponse<CardView.Reflection> reflection(Principal principal, @PathVariable String cardId,
            @RequestHeader("X-Couple-Id") String coupleId, @Valid @RequestBody Reflection request) {
        validateHeader(coupleId, 1, 80);
        return ApiResponse.success(cards.saveReflection(principal, coupleId, cardId, request));
    }

    @PostMapping("/cards/{cardId}/complete")
    @Operation(operationId = "completeCard")
    ApiResponse<CardView> complete(Principal principal, @PathVariable String cardId,
            @RequestHeader("X-Couple-Id") String coupleId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Complete request) {
        validateHeader(coupleId, 1, 80);
        validateHeader(key, 16, 128);
        return ApiResponse.success(cards.complete(principal, coupleId, cardId, key, request.expectedVersion()));
    }

    @PostMapping("/cards/{cardId}/discard")
    @Operation(operationId = "discardCard", summary = "작성하지 않은 카드 폐기")
    ResponseEntity<Void> discard(Principal principal, @PathVariable String cardId,
            @RequestHeader("X-Couple-Id") String coupleId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody Discard request) {
        validateHeader(coupleId, 1, 80);
        validateHeader(cardId, 1, 80);
        validateHeader(key, 16, 128);
        cards.discard(principal, coupleId, cardId, key, request.expectedVersion());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/cards/{cardId}")
    @Operation(operationId = "deleteCard")
    ResponseEntity<Void> delete(Principal principal, @PathVariable String cardId,
            @RequestHeader("X-Couple-Id") String coupleId,
            @RequestHeader(value = "X-Expected-Version", required = false) String version) {
        validateHeader(coupleId, 1, 80);
        validateHeader(cardId, 1, 80);
        if (version == null) {
            throw new CardError(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "카드 버전을 보내 주세요.");
        }
        int expected;
        try {
            expected = Integer.parseInt(version);
        } catch (NumberFormatException exception) {
            throw new CardError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "카드 버전을 확인해 주세요.");
        }
        if (expected < 1) {
            throw new CardError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "카드 버전을 확인해 주세요.");
        }
        cards.delete(principal, coupleId, cardId, expected);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(CardError.class)
    ResponseEntity<ErrorResponse> error(CardError error) {
        return ResponseEntity.status(error.status).body(ErrorResponse.of(error.code, error.getMessage(), List.of()));
    }

    private static void validateHeader(String value, int min, int max) {
        if (value.isBlank() || value.length() < min || value.length() > max) {
            throw new CardError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "요청 헤더를 확인해 주세요.");
        }
    }

    static final class Draft {
        @JsonSetter(nulls = Nulls.FAIL)
        @Size(min = 1, max = 50)
        private String title;

        @JsonSetter(nulls = Nulls.FAIL)
        private List<@NotBlank @Size(max = 80) String> photoIds;

        @JsonProperty
        @NotNull
        @Min(1)
        private Integer expectedVersion;

        String title() {
            return title;
        }

        List<String> photoIds() {
            return photoIds;
        }

        int expectedVersion() {
            return expectedVersion;
        }

        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown card draft field");
        }
    }

    record Reflection(@NotNull @Size(max = 70) String text, @NotNull Boolean submitted,
            @NotNull @Min(0) Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown reflection field");
        }
    }

    record Complete(@NotNull @Min(1) Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown card completion field");
        }
    }

    record Discard(@NotNull @Min(1) Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown card discard field");
        }
    }
}
