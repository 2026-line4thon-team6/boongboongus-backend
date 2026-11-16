package com.boongboongus.backend.common.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IdempotentWrite {
    private static final Duration WINDOW = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json;

    IdempotentWrite(JdbcClient jdbc, TransactionTemplate transaction, JsonMapper json) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
    }

    public <T> T run(String userId, String key, List<?> intent, Class<T> type, Supplier<T> action) {
        String fingerprint = fingerprint(intent);
        return Objects.requireNonNull(transaction.execute(status -> {
            boolean reserved = request("""
                    INSERT INTO api_idempotency (user_id, request_key, fingerprint, expires_at)
                    VALUES (:user, :key, :fingerprint, :expires) ON CONFLICT DO NOTHING
                    """, userId, key).param("fingerprint", fingerprint).param("expires", expiry()).update() == 1;
            if (!reserved) {
                Replay replay = request("""
                        SELECT fingerprint, expires_at, response_snapshot FROM api_idempotency
                        WHERE user_id = :user AND request_key = :key FOR UPDATE
                        """, userId, key).query((row, index) -> new Replay(row.getString("fingerprint"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        row.getString("response_snapshot"))).single();
                if (replay.expiresAt().isAfter(Instant.now())) {
                    if (!replay.fingerprint().equals(fingerprint)) {
                        throw new KeyReused();
                    }
                    return json.readValue(replay.response(), type);
                }
            }
            T result = action.get();
            request("""
                    UPDATE api_idempotency SET fingerprint = :fingerprint, expires_at = :expires,
                        response_snapshot = :response WHERE user_id = :user AND request_key = :key
                    """, userId, key).param("fingerprint", fingerprint).param("expires", expiry())
                    .param("response", json.writeValueAsString(result)).update();
            return result;
        }));
    }

    private JdbcClient.StatementSpec request(String sql, String userId, String key) {
        return jdbc.sql(sql).param("user", userId).param("key", key);
    }

    private String fingerprint(List<?> intent) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(intent)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static OffsetDateTime expiry() {
        return Instant.now().plus(WINDOW).atOffset(ZoneOffset.UTC);
    }

    private record Replay(String fingerprint, Instant expiresAt, String response) {
    }

    public static final class KeyReused extends RuntimeException {
    }
}
