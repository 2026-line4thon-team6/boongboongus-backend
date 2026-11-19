package com.boongboongus.backend.domain.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class VapidWebPushGatewayTest {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signsVapidTokenForPushServiceOrigin() throws Exception {
        KeyPair keys = WebPushCrypto.generateKeyPair();
        String publicKey = B64.encodeToString(WebPushCrypto.encodePoint((ECPublicKey) keys.getPublic()));
        String privateKey = B64.encodeToString(unsigned(((ECPrivateKey) keys.getPrivate()).getS().toByteArray()));
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        var gateway = new VapidWebPushGateway(new WebPushProperties(publicKey, privateKey, "mailto:team@example.test"),
                json, Clock.fixed(now, ZoneOffset.UTC));

        String header = gateway.authorization(URI.create("https://fcm.googleapis.com/fcm/send/abc"));

        assertThat(gateway.enabled()).isTrue();
        assertThat(header).startsWith("vapid t=").endsWith(", k=" + publicKey);
        String jwt = header.substring("vapid t=".length(), header.indexOf(','));
        String[] parts = jwt.split("\\.");
        JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.path("sub").asString()).isEqualTo("mailto:team@example.test");
        assertThat(claims.path("exp").asLong()).isEqualTo(now.plusSeconds(12 * 3600).getEpochSecond());
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void staysDisabledWithoutKeys() {
        var gateway = new VapidWebPushGateway(new WebPushProperties("", "", ""), json);

        assertThat(gateway.enabled()).isFalse();
    }

    @Test
    void rejectsMalformedKeysAtStartup() {
        assertThatThrownBy(() -> new VapidWebPushGateway(new WebPushProperties("abc", "def", "mailto:a@b.c"), json))
                .isInstanceOf(IllegalStateException.class);
    }

    private static byte[] unsigned(byte[] value) {
        byte[] fixed = new byte[32];
        int length = Math.min(value.length, 32);
        System.arraycopy(value, value.length - length, fixed, 32 - length, length);
        return fixed;
    }
}
