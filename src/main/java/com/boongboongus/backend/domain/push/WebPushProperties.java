package com.boongboongus.backend.domain.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * VAPID 키(base64url, 공개키 65바이트 · 개인키 32바이트)와 연락처(mailto: 또는 https:). 하나라도 비면 발송하지 않는다.
 */
@ConfigurationProperties(prefix = "app.push")
public record WebPushProperties(String vapidPublicKey, String vapidPrivateKey, String subject) {
    public boolean configured() {
        return notBlank(vapidPublicKey) && notBlank(vapidPrivateKey) && notBlank(subject);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
