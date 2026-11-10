package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
record DriveView(
        String id, String coupleId, String title, String status, String driverUserId,
        String passengerUserId, String recorderUserId, Place origin, Place destination,
        String routeId, Instant startedAt, Instant pausedAt, Instant endedAt, String endReason,
        int controlVersion, Metrics metrics, boolean canCreateCard, String cardId,
        Instant editableUntil, String playlistId) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Metrics(double distanceMeters, int activeDurationSeconds, int pausedDurationSeconds,
            Instant estimatedArrivalAt, Instant lastGpsAt, String gpsQuality) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Current(DriveView drive) {
    }
}
