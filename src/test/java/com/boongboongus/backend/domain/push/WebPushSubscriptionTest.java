package com.boongboongus.backend.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WebPushSubscriptionTest {
    private final JsonMapper json = JsonMapper.builder().build();

    static String subscription(String endpoint, String p256dh, String auth) {
        return """
                {"endpoint":"%s","expirationTime":null,"keys":{"p256dh":"%s","auth":"%s"}}
                """.formatted(endpoint, p256dh, auth);
    }

    static String validKey() throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(WebPushCrypto.encodePoint((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
    }

    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Test
    void parsesBrowserSubscriptionJson() throws Exception {
        var parsed = WebPushSubscription.parse(subscription("https://push.example.test/abc", validKey(), AUTH), json);

        assertThat(parsed.endpoint().getHost()).isEqualTo("push.example.test");
        assertThat(parsed.p256dh()).hasSize(65);
        assertThat(parsed.auth()).hasSize(16);
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain-token", "{}", "{\"endpoint\":\"https://push.example.test\"}"})
    void rejectsNonSubscriptionTokens(String token) {
        assertThatThrownBy(() -> WebPushSubscription.parse(token, json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInsecureEndpointAndWrongKeySizes() throws Exception {
        String key = validKey();
        assertThatThrownBy(() -> WebPushSubscription.parse(subscription("http://push.example.test/a", key, AUTH), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushSubscription.parse(subscription("https://push.example.test/a", AUTH, AUTH), json))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushSubscription.parse(subscription("https://push.example.test/a", key, key), json))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
