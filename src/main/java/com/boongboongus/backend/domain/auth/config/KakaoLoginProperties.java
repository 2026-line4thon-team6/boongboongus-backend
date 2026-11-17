package com.boongboongus.backend.domain.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth.kakao")
public record KakaoLoginProperties(String restApiKey, List<String> redirectUris, String clientSecret, String adminKey) {
    public KakaoLoginProperties {
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
    }
}
