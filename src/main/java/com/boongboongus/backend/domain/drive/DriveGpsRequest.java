package com.boongboongus.backend.domain.drive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

final class DriveGpsRequest {
    private DriveGpsRequest() {
    }

    record Batch(@NotNull @Size(min = 1, max = 200) List<@NotNull @Valid Sample> samples) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown GPS batch field");
        }
    }

    record Sample(
            @NotNull @Positive @JsonDeserialize(using = DrivePreparationRequest.ExactVersion.class) Integer sequence,
            @NotNull Instant recordedAt,
            @NotNull @Valid Position coordinate,
            @NotNull @DecimalMin("0") @DecimalMax("10000")
            @JsonDeserialize(using = DriveControlRequest.ExactCoordinateNumber.class) BigDecimal accuracyMeters,
            @JsonProperty(required = true) @DecimalMin("0")
            @JsonDeserialize(using = DriveControlRequest.ExactCoordinateNumber.class) BigDecimal speedMetersPerSecond) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown GPS sample field");
        }
    }

    record Position(
            @NotNull @JsonDeserialize(using = DriveControlRequest.ExactCoordinateNumber.class) BigDecimal latitude,
            @NotNull @JsonDeserialize(using = DriveControlRequest.ExactCoordinateNumber.class) BigDecimal longitude) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown GPS coordinate field");
        }
    }

    record End(
            @NotNull @Positive @JsonDeserialize(using = DrivePreparationRequest.ExactVersion.class) Integer expectedVersion,
            @NotNull EndReason reason,
            @NotNull @PositiveOrZero @JsonDeserialize(using = DrivePreparationRequest.ExactVersion.class)
            Integer lastRecordedSequence) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown drive end field");
        }
    }

    enum EndReason {
        ARRIVED, USER_ENDED
    }
}
