package com.boongboongus.backend.domain.home;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Supplies account, current couple, notification, and card data scoped to the authenticated user. */
public interface HomeDataSource {
    Optional<Summary> findForUser(String userId);

    record Summary(User me, Couple couple, int unreadNotificationCount, List<PendingCard> pendingCards) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record User(String id, String nickname, LocalDate birthDate, String profileImageUrl, String provider,
            boolean onboardingCompleted, String gender) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Member(String id, String nickname, LocalDate birthDate, String profileImageUrl, String provider) {
    }

    record Couple(String id, String status, List<Member> members, LocalDate startDate, int daysTogether,
            double cumulativeDistanceMeters, int completedCardCount, int version, String mascotName) {
    }

    record PendingCard(String cardId, String driveId, String title, String status, Instant editableUntil,
            long remainingSeconds, boolean hasDraftContent) {
    }
}
