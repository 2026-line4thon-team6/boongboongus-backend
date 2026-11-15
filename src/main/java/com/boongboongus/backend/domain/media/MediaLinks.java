package com.boongboongus.backend.domain.media;

import static com.boongboongus.backend.domain.media.MediaService.MediaError.notFound;
import static com.boongboongus.backend.domain.media.MediaService.MediaError.unavailable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class MediaLinks {
    private final String base;
    private final byte[] key;

    MediaLinks(@Value("${app.media.public-base-url}") String base,
            @Value("${app.media.signing-key}") String key) {
        this.base = base.replaceAll("/+$", "");
        this.key = key.getBytes(StandardCharsets.UTF_8);
        if (!base.isBlank()) {
            URI uri = URI.create(this.base);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid media public base URL");
            }
        }
    }

    void requireConfigured() {
        if (base.isBlank() || key.length < 32) {
            throw unavailable();
        }
    }

    String issue(String method, String id, String user, Instant expiresAt) {
        requireConfigured();
        String claim = expiresAt.getEpochSecond() + "." + encode(user.getBytes(StandardCharsets.UTF_8));
        return base + "/api/v1/media/" + id + "/content?token=" + claim + "." + encode(sign(method, id, claim));
    }

    String verify(String method, String id, String token) {
        requireConfigured();
        if (token == null || token.length() > 600) {
            throw notFound();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw notFound();
        }
        try {
            String claim = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(sign(method, id, claim), Base64.getUrlDecoder().decode(parts[2]))
                    || Long.parseLong(parts[0]) <= Instant.now().getEpochSecond()) {
                throw notFound();
            }
            return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw notFound();
        }
    }

    private byte[] sign(String method, String id, String claim) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((method + "\n" + id + "\n" + claim).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", error);
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
