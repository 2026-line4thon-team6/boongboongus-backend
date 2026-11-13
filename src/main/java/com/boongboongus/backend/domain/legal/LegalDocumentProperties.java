package com.boongboongus.backend.domain.legal;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.legal")
public record LegalDocumentProperties(List<@Valid LegalDocumentResponse> documents) {

    public LegalDocumentProperties {
        documents = documents == null ? List.of() : List.copyOf(documents);
    }
}
