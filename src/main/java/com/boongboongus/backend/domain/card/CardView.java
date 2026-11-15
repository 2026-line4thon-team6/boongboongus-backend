package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.domain.couple.dto.CurrentCoupleResponse.Member;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
record CardView(String id, String driveId, String driverUserId, String coupleId, String status, String title,
        LocalDate driveDate, String region, Place destination, double distanceMeters, int activeDurationSeconds,
        int stampCount, Playlist playlist, List<Photo> photos, List<Reflection> reflections, Instant editableUntil,
        Instant completedAt, boolean canEdit, boolean canDelete, int version) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Playlist(String id, String title, int trackCount, int durationSeconds, String thumbnailUrl) {
    }

    record Photo(String mediaId, String url, Instant urlExpiresAt, int order) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Reflection(Member author, String role, String text, boolean submitted, Instant submittedAt,
            Instant updatedAt, int version) {
    }
}
