package com.boongboongus.backend.domain.drive;

import java.util.Optional;

/** Resolves the current connection from server-side membership data for an authenticated user. */
public interface CurrentCoupleLookup {
    Optional<Couple> findCurrent(String userId);

    record Couple(String id, String firstMemberId, String secondMemberId) {
        boolean contains(String userId) {
            return userId.equals(firstMemberId) || userId.equals(secondMemberId);
        }
    }
}
