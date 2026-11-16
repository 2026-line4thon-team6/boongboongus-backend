package com.boongboongus.backend.domain.home;

import java.time.Instant;

/** Persists an in-app notification in the caller's transaction; the push is dispatched separately. */
public interface PlaceShareNotification {
    Receipt create(String coupleId, String senderUserId, String recipientUserId, String placeId);

    record Receipt(String notificationId, Instant sentAt) {
    }
}
