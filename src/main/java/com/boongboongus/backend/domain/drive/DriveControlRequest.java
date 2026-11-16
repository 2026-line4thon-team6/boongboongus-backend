package com.boongboongus.backend.domain.drive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.util.AccessPattern;

final class DriveControlRequest {
    private DriveControlRequest() {
    }

    record Patch(
            @JsonDeserialize(using = PatchString.class) @Size(max = 50) String title,
            @JsonDeserialize(using = PatchString.class) @Size(min = 1, max = 80) String routeId,
            @NotNull @Positive @JsonDeserialize(using = DrivePreparationRequest.ExactVersion.class)
            Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown drive patch field");
        }
    }

    static final class PatchString extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return parser.getString();
            }
            return context.reportInputMismatch(String.class, "Drive patch value must be a string");
        }

        @Override
        public String getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(String.class, "Drive patch value cannot be null");
        }

        @Override
        public AccessPattern getNullAccessPattern() {
            return AccessPattern.DYNAMIC;
        }

        @Override
        public String getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    record Start(
            @NotNull @Positive @JsonDeserialize(using = DrivePreparationRequest.ExactVersion.class)
            Integer expectedVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Valid InitialCoordinate initialCoordinate) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown drive start field");
        }
    }

    record InitialCoordinate(
            @NotNull @DecimalMin("-90") @DecimalMax("90")
            @JsonDeserialize(using = ExactCoordinateNumber.class) BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180")
            @JsonDeserialize(using = ExactCoordinateNumber.class) BigDecimal longitude) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown coordinate field");
        }
    }

    static final class ExactCoordinateNumber extends ValueDeserializer<BigDecimal> {
        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken().isNumeric()) {
                return parser.getDecimalValue().stripTrailingZeros();
            }
            return context.reportInputMismatch(BigDecimal.class, "Coordinate must be a number");
        }
    }
}
