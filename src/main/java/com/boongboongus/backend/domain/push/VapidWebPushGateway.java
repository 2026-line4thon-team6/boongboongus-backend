package com.boongboongus.backend.domain.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * VAPID(RFC 8292) 서명을 붙여 Web Push(RFC 8030)로 보낸다. 키가 설정되지 않았으면 아무것도 보내지 않는다.
 */
@Component
@EnableConfigurationProperties(WebPushProperties.class)
class VapidWebPushGateway implements WebPushGateway {
    private static final Logger log = LoggerFactory.getLogger(VapidWebPushGateway.class);
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    // 푸시 서비스가 기기에 전달하지 못하면 보관하는 시간. 실시간 알림이라 하루면 충분하다
    private static final int TTL_SECONDS = 24 * 60 * 60;
    private static final Duration TOKEN_LIFETIME = Duration.ofHours(12);

    private final WebPushProperties properties;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ECPrivateKey privateKey;

    @Autowired
    VapidWebPushGateway(WebPushProperties properties, JsonMapper json) {
        this(properties, json, Clock.systemUTC());
    }

    VapidWebPushGateway(WebPushProperties properties, JsonMapper json, Clock clock) {
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.privateKey = properties.configured() ? loadKey(properties) : null;
    }

    @Override
    public boolean enabled() {
        return privateKey != null;
    }

    @Override
    public Result send(WebPushSubscription subscription, byte[] payload) {
        if (!enabled()) {
            return Result.FAILED;
        }
        try {
            byte[] body = WebPushCrypto.encrypt(payload, subscription.p256dh(), subscription.auth());
            HttpRequest request = HttpRequest.newBuilder(subscription.endpoint())
                    .timeout(Duration.ofSeconds(10))
                    .header("TTL", Integer.toString(TTL_SECONDS))
                    .header("Urgency", "normal")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", authorization(subscription.endpoint()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 404 || status == 410) {
                return Result.GONE;
            }
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            // 구독 주소는 기기 식별 정보라 로그에 남기지 않는다
            log.warn("Web push rejected with status {}", status);
            return Result.FAILED;
        } catch (IOException exception) {
            log.warn("Web push delivery failed: {}", exception.getClass().getSimpleName());
            return Result.FAILED;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        }
    }

    String authorization(URI endpoint) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", endpoint.getScheme() + "://" + endpoint.getRawAuthority());
        claims.put("exp", clock.instant().plus(TOKEN_LIFETIME).getEpochSecond());
        claims.put("sub", properties.subject());
        String unsigned = encode(Map.of("typ", "JWT", "alg", "ES256")) + "." + encode(claims);
        try {
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(privateKey);
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            String jwt = unsigned + "." + BASE64URL.encodeToString(signer.sign());
            return "vapid t=" + jwt + ", k=" + properties.vapidPublicKey().replace("=", "");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("VAPID signing failed", exception);
        }
    }

    private String encode(Map<String, Object> value) {
        return BASE64URL.encodeToString(json.writeValueAsBytes(value));
    }

    private static ECPrivateKey loadKey(WebPushProperties properties) {
        try {
            byte[] publicKey = Base64.getUrlDecoder().decode(properties.vapidPublicKey().replace("=", ""));
            WebPushCrypto.publicKey(publicKey);
            return WebPushCrypto.privateKey(Base64.getUrlDecoder().decode(properties.vapidPrivateKey().replace("=", "")));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid VAPID key configuration", exception);
        }
    }
}
