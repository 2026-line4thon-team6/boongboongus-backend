package com.boongboongus.backend.domain.drive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

record DrivePreparationRequest(
        @Size(max = 50) String title,
        @NotBlank @Size(max = 80) String driverUserId,
        @NotBlank @Size(max = 80) String passengerUserId,
        @NotBlank @Size(max = 80) String recorderUserId,
        @Size(min = 1, max = 80) String routeId) {

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown drive preparation field");
    }

    record Version(@NotNull @Positive @JsonDeserialize(using = ExactVersion.class) Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("Unknown drive control field");
        }
    }

    static final class ExactVersion extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken().isNumeric()) {
                try {
                    return parser.getDecimalValue().intValueExact();
                } catch (ArithmeticException ignored) {
                    return context.reportInputMismatch(Integer.class, "expectedVersion must be an integer");
                }
            }
            return context.reportInputMismatch(Integer.class, "expectedVersion must be an integer");
        }
    }
}
