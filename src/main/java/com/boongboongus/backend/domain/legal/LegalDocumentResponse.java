package com.boongboongus.backend.domain.legal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;

public record LegalDocumentResponse(
        @NotNull Type type,
        @NotBlank String version,
        @NotBlank String title,
        @NotNull URI url,
        @NotBlank String content,
        @NotNull @DateTimeFormat(iso = ISO.DATE) LocalDate effectiveDate) {

    public boolean isRequired() {
        return type == Type.TERMS || type == Type.PRIVACY;
    }

    public enum Type {
        TERMS, PRIVACY, OPEN_SOURCE
    }
}
