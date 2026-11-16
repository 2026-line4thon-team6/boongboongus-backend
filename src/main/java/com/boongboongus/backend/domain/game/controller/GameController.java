package com.boongboongus.backend.domain.game.controller;

import com.boongboongus.backend.common.api.ApiResponse;
import com.boongboongus.backend.common.api.ErrorResponse;
import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.game.dto.GameDtos.Create;
import com.boongboongus.backend.domain.game.dto.GameDtos.Input;
import com.boongboongus.backend.domain.game.dto.GameDtos.Question;
import com.boongboongus.backend.domain.game.dto.GameDtos.Response;
import com.boongboongus.backend.domain.game.dto.GameDtos.Session;
import com.boongboongus.backend.domain.game.dto.GameDtos.Version;
import com.boongboongus.backend.domain.game.service.GameService;
import com.boongboongus.backend.domain.game.service.GameService.Action;
import com.boongboongus.backend.domain.job.dto.JobResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/api/v1/game-sessions")
class GameController {
    private final GameService games;

    GameController(GameService games) {
        this.games = games;
    }

    @PostMapping
    @Operation(operationId = "createGameSession")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<JobResponse> create(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Create request) {
        return ApiResponse.success(games.create(principal.getName(), couple(couple), key(key), request));
    }

    @GetMapping("/{gameSessionId}")
    @Operation(operationId = "getGameSession")
    ResponseEntity<ApiResponse<Session>> get(Principal principal, @PathVariable String gameSessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(games.get(principal.getName(), gameSessionId)));
    }

    @PostMapping("/{gameSessionId}/questions/{questionId}/hint")
    @Operation(operationId = "revealHint")
    ApiResponse<Question> hint(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String gameSessionId,
            @PathVariable String questionId) {
        return ApiResponse.success(games.reveal(principal.getName(), couple(couple), key(key), gameSessionId,
                questionId, false));
    }

    @PostMapping("/{gameSessionId}/questions/{questionId}/answer")
    @Operation(operationId = "revealAnswer")
    ApiResponse<Question> answer(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String gameSessionId,
            @PathVariable String questionId) {
        return ApiResponse.success(games.reveal(principal.getName(), couple(couple), key(key), gameSessionId,
                questionId, true));
    }

    @PutMapping("/{gameSessionId}/questions/{questionId}/responses/me")
    @Operation(operationId = "saveGameResponse")
    ApiResponse<Response> save(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @PathVariable String gameSessionId, @PathVariable String questionId, @Valid @RequestBody Input input) {
        return ApiResponse.success(games.save(principal.getName(), couple(couple), gameSessionId, questionId, input));
    }

    @PostMapping("/{gameSessionId}/next")
    @Operation(operationId = "nextGameQuestion")
    ApiResponse<Session> next(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String gameSessionId,
            @Valid @RequestBody Version request) {
        return progress(principal, couple, key, gameSessionId, request, Action.NEXT);
    }

    @PostMapping("/{gameSessionId}/complete")
    @Operation(operationId = "completeGameSession")
    ApiResponse<Session> complete(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String gameSessionId,
            @Valid @RequestBody Version request) {
        return progress(principal, couple, key, gameSessionId, request, Action.COMPLETE);
    }

    @PostMapping("/{gameSessionId}/abandon")
    @Operation(operationId = "abandonGame")
    ApiResponse<Session> abandon(Principal principal, @RequestHeader("X-Couple-Id") String couple,
            @RequestHeader("Idempotency-Key") String key, @PathVariable String gameSessionId,
            @Valid @RequestBody Version request) {
        return progress(principal, couple, key, gameSessionId, request, Action.ABANDON);
    }

    private ApiResponse<Session> progress(Principal principal, String couple, String key, String id,
            Version request, Action action) {
        return ApiResponse.success(games.progress(principal.getName(), couple(couple), key(key), id, request, action));
    }

    @ExceptionHandler(GameService.Rejected.class)
    ResponseEntity<ErrorResponse> rejected(GameService.Rejected exception) {
        return ResponseEntity.status(exception.status())
                .body(ErrorResponse.of(exception.code(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(IdempotentWrite.KeyReused.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse keyReused() {
        return ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", "같은 키로 다른 요청을 보냈습니다.", List.of());
    }

    @ExceptionHandler(InvalidHeader.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse invalidHeader(InvalidHeader exception) {
        return ErrorResponse.of("BAD_REQUEST", exception.getMessage(), List.of());
    }

    private static String key(String key) {
        if (key.isBlank() || key.length() < 16 || key.length() > 128) {
            throw new InvalidHeader("Idempotency-Key 값을 확인해 주세요.");
        }
        return key;
    }

    private static String couple(String couple) {
        if (couple.isBlank() || couple.length() > 80) {
            throw new InvalidHeader("X-Couple-Id 값을 확인해 주세요.");
        }
        return couple;
    }

    private static final class InvalidHeader extends RuntimeException {
        InvalidHeader(String message) {
            super(message);
        }
    }
}
