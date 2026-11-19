package com.boongboongus.backend.domain.push;

import java.net.URI;
import java.util.Base64;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 브라우저 PushSubscription(JSON)에서 발송에 필요한 값. platform=WEB 기기의 push_token에 JSON 문자열로 저장한다.
 */
public record WebPushSubscription(URI endpoint, byte[] p256dh, byte[] auth) {
    private static final int MAX_LENGTH = 2048;

    public static WebPushSubscription parse(String token, JsonMapper json) {
        if (token == null || token.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid web push subscription");
        }
        try {
            JsonNode root = json.readTree(token);
            URI endpoint = URI.create(text(root.path("endpoint")));
            byte[] p256dh = decode(text(root.path("keys").path("p256dh")));
            byte[] auth = decode(text(root.path("keys").path("auth")));
            // 푸시 서비스는 https만, 수신 공개키는 P-256 비압축 점(65바이트), 인증 비밀은 16바이트 (RFC 8291)
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || p256dh.length != 65 || p256dh[0] != 0x04 || auth.length != 16) {
                throw new IllegalArgumentException("Invalid web push subscription");
            }
            return new WebPushSubscription(endpoint, p256dh, auth);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid web push subscription", exception);
        }
    }

    private static String text(JsonNode node) {
        if (!node.isString() || node.asString().isBlank()) {
            throw new IllegalArgumentException("Invalid web push subscription");
        }
        return node.asString();
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value.replace("=", ""));
    }
}
