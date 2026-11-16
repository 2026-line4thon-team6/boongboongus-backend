package com.boongboongus.backend.domain.faq;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FaqResponse(
        @NotBlank @Size(max = 80) String id,
        @NotBlank String question,
        @NotBlank String answer,
        @NotNull Integer order) {
}
