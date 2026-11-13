package com.boongboongus.backend.domain.faq;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.faq")
public record FaqProperties(List<@Valid FaqResponse> items) {

    public FaqProperties {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
