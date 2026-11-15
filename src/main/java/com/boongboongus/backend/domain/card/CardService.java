package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.auth.repository.AccountRepository;
import com.boongboongus.backend.domain.card.CardStore.CardRow;
import com.boongboongus.backend.domain.card.CardStore.ReflectionRow;
import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse.Member;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DrivePreparationService;
import com.boongboongus.backend.domain.media.MediaService;
import com.boongboongus.backend.domain.notification.service.NotificationService;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class CardService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CardStore cards;
    private final ObjectProvider<CurrentCoupleLookup> couples;
    private final AccountRepository accounts;
    private final MediaService media;
    private final DrivePreparationService drives;
    private final NotificationService notifications;
    private final IdempotentWrite idempotency;
    private final TransactionTemplate transactions;

    CardService(CardStore cards, ObjectProvider<CurrentCoupleLookup> couples, AccountRepository accounts,
            MediaService media, DrivePreparationService drives, NotificationService notifications,
            IdempotentWrite idempotency,
            TransactionTemplate transactions) {
        this.cards = cards;
        this.couples = couples;
        this.accounts = accounts;
        this.media = media;
        this.drives = drives;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.transactions = transactions;
    }

    CardView get(Principal principal, String id, boolean byDrive) {
        var actor = actor(principal, null);
        // Hold the card while reading its photos and reflections for a consistent response.
        return transactions.execute(status -> {
            String cardId = byDrive ? cards.find(actor.couple(), id, true).orElseThrow(CardError::unavailable).id() : id;
            CardRow card = lockCard(actor, cardId);
            Instant now = Instant.now();
            if (!card.completed() && !now.isBefore(card.editableUntil())) {
                throw new CardError(HttpStatus.GONE, byDrive ? "CARD_EXPIRED" : "CARD_EDIT_WINDOW_CLOSED",
                        "카드 작성 시간이 지났습니다.");
            }
            return view(card, actor.user(), now);
        });
    }

    CardView draft(Principal principal, String coupleId, String id, CardController.Draft request) {
        var actor = actor(principal, coupleId);
        if (request.title() == null && request.photoIds() == null) {
            throw invalid("VALIDATION_FAILED", "제목 또는 사진을 확인해 주세요.");
        }
        if (request.title() != null && request.title().isBlank()) {
            throw invalid("VALIDATION_FAILED", "카드 제목을 확인해 주세요.");
        }
        if (request.photoIds() != null) {
            if (request.photoIds().size() > 2) {
                throw invalid("PHOTO_LIMIT_EXCEEDED", "사진은 최대 두 장까지 첨부할 수 있습니다.");
            }
            if (new HashSet<>(request.photoIds()).size() != request.photoIds().size()) {
                throw invalid("VALIDATION_FAILED", "사진을 중복해서 첨부할 수 없습니다.");
            }
        }
        return transactions.execute(status -> {
            CardRow card = lockForWrite(actor, id);
            Instant now = Instant.now();
            ensureEditable(card, now);
            ensureVersion(card.version(), request.expectedVersion());
            if (request.photoIds() != null) {
                if (card.completed() && request.photoIds().isEmpty() && !cards.hasSubmittedReflection(id)) {
                    throw contentRequired();
                }
                if (!media.replaceCardPhotos(id, actor.couple(), actor.user(), request.photoIds(), now)) {
                    throw invalid("MEDIA_NOT_READY", "첨부할 수 있는 사진인지 확인해 주세요.");
                }
            }
            cards.updateDraft(card, request.title() == null ? card.title() : request.title().strip());
            return view(find(actor, id), actor.user(), now);
        });
    }

    CardView.Reflection saveReflection(Principal principal, String coupleId, String id,
            CardController.Reflection request) {
        var actor = actor(principal, coupleId);
        String text = request.text().strip();
        if (request.submitted() && text.isEmpty()) {
            throw invalid("EMPTY_REFLECTION", "제출할 소감을 입력해 주세요.");
        }
        return transactions.execute(status -> {
            CardRow card = lockForWrite(actor, id);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            ensureEditable(card, now);
            var previous = cards.reflection(id, actor.user()).orElse(null);
            if (previous != null && previous.submittedAt() != null && !request.submitted()) {
                throw invalid("VALIDATION_FAILED", "제출한 소감은 임시저장으로 되돌릴 수 없습니다.");
            }
            if (previous != null && previous.version() - 1 == request.expectedVersion()
                    && previous.text().equals(text) && (previous.submittedAt() != null) == request.submitted()) {
                return reflectionView(card, previous);
            }
            int version = previous == null ? 0 : previous.version();
            ensureVersion(version, request.expectedVersion());
            Instant submittedAt = previous == null ? null : previous.submittedAt();
            boolean firstSubmission = request.submitted() && submittedAt == null;
            var saved = new ReflectionRow(actor.user(), text, firstSubmission ? now : submittedAt, now, version + 1);
            cards.saveReflection(id, saved);
            if (!text.isEmpty()) {
                cards.markDraft(id);
            }
            if (firstSubmission) {
                notifications.cardActivity(actor.user(), partner(card, actor.user()), id, false, now);
            }
            return reflectionView(card, saved);
        });
    }

    CardView complete(Principal principal, String coupleId, String id, String key, int version) {
        var actor = actor(principal, coupleId);
        // Re-check current authorization before returning an idempotent response.
        find(actor, id);
        try {
            return idempotency.run(actor.user(), key, List.of("complete-card", coupleId, id, version), CardView.class, () -> {
                CardRow card = lockForWrite(actor, id);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                ensureEditable(card, now);
                ensureVersion(card.version(), version);
                if (!card.completed()) {
                    if (!cards.hasSubmittedReflection(id) && !media.hasCardPhotos(id)) {
                        throw contentRequired();
                    }
                    cards.complete(id, now);
                    String recipient = partner(card, actor.user());
                    notifications.cardActivity(actor.user(), recipient, id, true, now);
                    cards.queueCompletionPush(id, recipient, now);
                }
                return view(find(actor, id), actor.user(), now);
            });
        } catch (IdempotentWrite.KeyReused error) {
            throw new CardError(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "요청 키가 다른 요청에 사용되었습니다.");
        }
    }

    void delete(Principal principal, String coupleId, String id, int expectedVersion) {
        Actor actor = actor(principal, coupleId);
        transactions.executeWithoutResult(status -> {
            CardRow card = lockForWrite(actor, id);
            ensureVersion(card.version(), expectedVersion);
            if (!card.completed()) {
                throw invalid("VALIDATION_FAILED", "완료한 카드만 삭제할 수 있습니다.");
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            drives.purgeCardGps(actor.couple(), card.driveId(), now);
            media.replaceCardPhotos(id, actor.couple(), null, List.of(), now);
            cards.delete(id);
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void expireCards() {
        Instant now = Instant.now();
        for (var item : cards.expired(now)) {
            transactions.executeWithoutResult(status -> cards.lock(item.coupleId(), item.id()).ifPresent(card -> {
                if (!card.completed() && !"EXPIRED".equals(card.status()) && !now.isBefore(card.editableUntil())) {
                    media.replaceCardPhotos(card.id(), card.coupleId(), null, List.of(), now);
                    cards.expire(card);
                }
            }));
        }
    }

    private CardView view(CardRow card, String user, Instant now) {
        var photos = new ArrayList<CardView.Photo>();
        for (var photo : media.cardPhotos(card.id(), user)) {
            photos.add(new CardView.Photo(photo.mediaId(), photo.url(), photo.expiresAt(), photos.size()));
        }
        var reflections = cards.visibleReflections(card.id(), user).stream()
                .map(reflection -> reflectionView(card, reflection)).toList();
        return new CardView(card.id(), card.driveId(), card.driver(), card.coupleId(), card.status(), card.title(),
                card.startedAt().atZone(SEOUL).toLocalDate(), region(card), card.destination(), card.distance(),
                card.duration(), card.stamps(), card.playlist(), photos, reflections, card.editableUntil(),
                card.completedAt(), now.isBefore(card.editableUntil()), card.completed(), card.version());
    }

    private CardView.Reflection reflectionView(CardRow card, ReflectionRow reflection) {
        var account = accounts.findById(reflection.author()).orElseThrow(CardError::notFound);
        var member = new Member(account.id(), account.nickname(), account.birthDate(), account.profileImageUrl(),
                account.provider());
        return new CardView.Reflection(member, card.driver().equals(account.id()) ? "DRIVER" : "PASSENGER",
                reflection.text(), reflection.submittedAt() != null, reflection.submittedAt(), reflection.updatedAt(),
                reflection.version());
    }

    private static String region(CardRow card) {
        if (card.destination() == null || card.destination().address() == null) {
            return "";
        }
        String[] address = card.destination().address().strip().split("\\s+", 3);
        return address.length < 2 ? address[0] : address[0] + " " + address[1];
    }

    private CardRow lockForWrite(Actor actor, String id) {
        if (!lookup().lockCurrent(actor.user(), actor.couple())) {
            throw contextChanged();
        }
        return lockCard(actor, id);
    }

    private CardRow lockCard(Actor actor, String id) {
        return cards.lock(actor.couple(), id).filter(card -> participates(actor, card))
                .orElseThrow(CardError::notFound);
    }

    private CardRow find(Actor actor, String id) {
        return cards.find(actor.couple(), id, false).filter(card -> participates(actor, card))
                .orElseThrow(CardError::notFound);
    }

    private static boolean participates(Actor actor, CardRow card) {
        return actor.user().equals(card.driver()) || actor.user().equals(card.passenger());
    }

    private static String partner(CardRow card, String user) {
        return user.equals(card.driver()) ? card.passenger() : card.driver();
    }

    Actor actor(Principal principal, String requestedCouple) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new CardError(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
        }
        String user = principal.getName();
        var couple = lookup().findCurrent(user)
                .orElseThrow(() -> new CardError(HttpStatus.FORBIDDEN, "COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
        if (!user.equals(couple.firstMemberId()) && !user.equals(couple.secondMemberId())) {
            throw contextChanged();
        }
        if (requestedCouple != null && !requestedCouple.equals(couple.id())) {
            throw contextChanged();
        }
        return new Actor(user, couple.id());
    }

    private CurrentCoupleLookup lookup() {
        var lookup = couples.getIfAvailable();
        if (lookup == null) {
            throw new CardError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "커플 정보를 확인할 수 없습니다.");
        }
        return lookup;
    }

    private static void ensureVersion(int actual, int expected) {
        if (actual != expected) {
            throw new CardError(HttpStatus.CONFLICT, "VERSION_CONFLICT", "최신 내용을 조회해 주세요.");
        }
    }

    private static void ensureEditable(CardRow card, Instant now) {
        if (!now.isBefore(card.editableUntil())) {
            throw new CardError(HttpStatus.GONE, "CARD_EDIT_WINDOW_CLOSED", "카드 작성 시간이 지났습니다.");
        }
    }

    private static CardError contextChanged() {
        return new CardError(HttpStatus.FORBIDDEN, "COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
    }

    private static CardError contentRequired() {
        return invalid("CARD_CONTENT_REQUIRED", "사진 또는 제출한 소감이 필요합니다.");
    }

    private static CardError invalid(String code, String message) {
        return new CardError(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    record Actor(String user, String couple) {
    }

    static final class CardError extends RuntimeException {
        final HttpStatus status;
        final String code;

        CardError(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        static CardError notFound() {
            return new CardError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "카드를 찾을 수 없습니다.");
        }

        static CardError unavailable() {
            return new CardError(HttpStatus.NOT_FOUND, "CARD_UNAVAILABLE", "작성 가능한 카드가 없습니다.");
        }
    }
}
