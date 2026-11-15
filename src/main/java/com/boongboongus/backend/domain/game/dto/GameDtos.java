package com.boongboongus.backend.domain.game.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class GameDtos {
    private GameDtos() {
    }

    public enum Type { BALANCE, NONSENSE, MEMORY }
    public enum Choice { A, B }
    public enum Role { DRIVER, PASSENGER }
    public enum Status { ACTIVE, COMPLETED, ABANDONED }

    public record Create(@NotBlank @Size(max = 80) String driveId, @NotNull Type type) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown game request field");
        }
    }

    public record Input(@NotNull Type type, Choice choice, Role selectedRole) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown game request field");
        }
    }

    public record Version(@NotNull @Positive @JsonDeserialize(using = ExactVersion.class) Integer expectedVersion) {
        @JsonAnySetter
        void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown game version field");
        }
    }

    static final class ExactVersion extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken().isNumeric()) {
                try {
                    return parser.getDecimalValue().intValueExact();
                } catch (ArithmeticException ignored) {
                    // Reject fractions and values outside the integer range.
                }
            }
            return context.reportInputMismatch(Integer.class, "expectedVersion must be an integer");
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Question(String id, int number, String prompt, String optionA, String optionB, String hint,
            String answer, boolean hintRevealed, boolean answerRevealed) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Response(String authorUserId, Choice choice, Role selectedRole, Instant updatedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Session(String id, String driveId, Type type, Status status, int questionCount,
            Question currentQuestion, List<Response> responses, int version, Instant createdAt, Instant completedAt) {
    }
}
