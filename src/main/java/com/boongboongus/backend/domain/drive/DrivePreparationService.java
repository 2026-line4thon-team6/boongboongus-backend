package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup.Couple;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.DriveRow;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.RequestKey;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.StoredRequest;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Route;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
class DrivePreparationService {
    private static final Duration REPLAY_WINDOW = Duration.ofHours(24);
    private static final String DEFAULT_TITLE = "우리의 드라이브";

    private final DrivePreparationStore store;
    private final ObjectProvider<CurrentCoupleLookup> couples;
    private final ObjectProvider<DriveRouteLookup> routes;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    DrivePreparationService(DrivePreparationStore store, ObjectProvider<CurrentCoupleLookup> couples,
            ObjectProvider<DriveRouteLookup> routes, TransactionTemplate transaction, JsonMapper json) {
        this.store = store;
        this.couples = couples;
        this.routes = routes;
        this.transaction = transaction;
        this.json = json;
    }

    DriveView prepare(Principal principal, String coupleId, String key, DrivePreparationRequest request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("prepare", request), () -> {
            validateRoles(actor.couple(), request);
            Route route = request.routeId() == null ? null : loadRoute(actor, request.routeId());
            String title = request.title() == null ? "" : request.title().strip();
            DriveRow drive = new DriveRow(
                    "drv_" + UUID.randomUUID(), coupleId, title.isEmpty() ? DEFAULT_TITLE : title,
                    "PREPARING", request.driverUserId(), request.passengerUserId(), request.recorderUserId(),
                    request.routeId(), route == null ? null : route.origin(),
                    route == null ? null : route.destination(), 1);
            if (!store.insert(drive)) {
                throw error(HttpStatus.CONFLICT, "ACTIVE_DRIVE_EXISTS", "커플에 이미 진행 가능한 주행이 있습니다.");
            }
            return drive.view();
        });
    }

    DriveView active(Principal principal) {
        Actor actor = currentActor(principal);
        return store.findActive(actor.couple().id()).map(DriveRow::view).orElse(null);
    }

    DriveView cancel(Principal principal, String coupleId, String key, String driveId,
            DrivePreparationRequest.Cancel request) {
        Actor actor = currentActor(principal);
        requireMatchingCouple(actor, coupleId);
        return executeOnce(actor, key, List.of("cancel", driveId, request), () -> {
            DriveRow drive = store.lockDrive(coupleId, driveId)
                    .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "주행을 찾을 수 없습니다."));
            if (drive.controlVersion() != request.expectedVersion()) {
                throw error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "주행 정보를 다시 조회해 주세요.");
            }
            if (!"PREPARING".equals(drive.status())) {
                throw error(HttpStatus.CONFLICT, "DRIVE_STATE_CONFLICT", "준비 상태의 주행만 취소할 수 있습니다.");
            }
            store.cancel(drive);
            return drive.view("CANCELLED", drive.controlVersion() + 1);
        });
    }

    private DriveView executeOnce(Actor actor, String key, List<?> intent, Supplier<DriveView> action) {
        RequestKey requestKey = new RequestKey(actor.couple().id(), actor.userId(), key);
        String fingerprint = fingerprint(intent);
        return Objects.requireNonNull(transaction.execute(status -> {
            Instant now = Instant.now();
            // A concurrent retry waits on the unique key until the first transaction commits.
            if (!store.reserve(requestKey, fingerprint, now.plus(REPLAY_WINDOW))) {
                StoredRequest previous = store.lockRequest(requestKey);
                now = Instant.now();
                if (previous.expiresAt().isAfter(now)) {
                    if (!fingerprint.equals(previous.fingerprint())) {
                        throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                                "같은 멱등 키에 다른 요청을 사용할 수 없습니다.");
                    }
                    return json.readValue(previous.responseSnapshot(), DriveView.class);
                }
            }
            DriveView result = action.get();
            store.saveResult(requestKey, fingerprint, Instant.now().plus(REPLAY_WINDOW), result);
            return result;
        }));
    }

    private String fingerprint(List<?> intent) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(intent));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Actor currentActor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        String userId = principal.getName();
        CurrentCoupleLookup lookup = couples.getIfAvailable();
        if (lookup == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "커플 정보를 확인할 수 없습니다.");
        }
        Couple couple = lookup.findCurrent(userId)
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
        if (!couple.contains(userId)) {
            throw error(HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "이 커플에 접근할 수 없습니다.");
        }
        return new Actor(userId, couple);
    }

    private void requireMatchingCouple(Actor actor, String requestedCoupleId) {
        if (!actor.couple().id().equals(requestedCoupleId)) {
            throw error(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
        }
    }

    private void validateRoles(Couple couple, DrivePreparationRequest request) {
        String driver = request.driverUserId();
        String passenger = request.passengerUserId();
        String recorder = request.recorderUserId();
        if (driver.equals(passenger) || !couple.contains(driver) || !couple.contains(passenger)
                || (!recorder.equals(driver) && !recorder.equals(passenger))) {
            throw error(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_DRIVE_MEMBERS",
                    "운전자, 동승자, 기록 기기의 사용자 지정을 확인해 주세요.");
        }
    }

    private Route loadRoute(Actor actor, String routeId) {
        DriveRouteLookup lookup = routes.getIfAvailable();
        if (lookup == null) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "경로 정보를 확인할 수 없습니다.");
        }
        return lookup.findAccessible(actor.userId(), actor.couple().id(), routeId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "경로를 찾을 수 없습니다."));
    }

    private static DriveApiException error(HttpStatus status, String code, String message) {
        return new DriveApiException(status, code, message);
    }

    private record Actor(String userId, Couple couple) {
    }
}
