package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.common.api.IdempotentWrite;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class PlaceSearchHistory {
    private static final RowMapper<Entry> ENTRY_MAPPER = (row, index) -> new Entry(row.getString("id"),
            row.getString("query"), row.getObject("searched_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final IdempotentWrite idempotency;

    PlaceSearchHistory(JdbcClient jdbc, IdempotentWrite idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    List<Entry> list(String userId) {
        return jdbc.sql("""
                SELECT id, query, searched_at FROM place_search_history
                WHERE user_id = :user ORDER BY searched_at DESC, id
                """).param("user", userId).query(ENTRY_MAPPER).list();
    }

    Entry save(String userId, String key, String query) {
        return idempotency.run(userId, key, List.of("search-history", query), Entry.class,
                () -> upsert(userId, query));
    }

    void delete(String userId, String historyId) {
        int affected = jdbc.sql("DELETE FROM place_search_history WHERE user_id = :user AND id = :id")
                .param("user", userId).param("id", historyId).update();
        if (affected == 0) {
            throw HomeExploreService.notFound("최근 검색어를 찾을 수 없습니다.");
        }
    }

    private Entry upsert(String userId, String query) {
        // The per-user row serializes the upsert and pruning, including writes with different request keys.
        jdbc.sql("INSERT INTO place_search_user (user_id) VALUES (:user) ON CONFLICT DO NOTHING")
                .param("user", userId).update();
        jdbc.sql("SELECT user_id FROM place_search_user WHERE user_id = :user FOR UPDATE")
                .param("user", userId).query(String.class).single();
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ");
        var stored = jdbc.sql("""
                SELECT id FROM place_search_history WHERE user_id = :user AND normalized_query = :normalized FOR UPDATE
                """).param("user", userId).param("normalized", normalized).query(String.class).optional();
        String id = stored.orElseGet(() -> "srch_" + UUID.randomUUID());
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var latest = jdbc.sql("""
                SELECT searched_at FROM place_search_history
                WHERE user_id = :user ORDER BY searched_at DESC LIMIT 1
                """).param("user", userId).query(OffsetDateTime.class).optional();
        if (latest.isPresent() && !now.isAfter(latest.get().toInstant())) {
            now = latest.get().toInstant().plus(1, ChronoUnit.MICROS);
        }
        if (stored.isPresent()) {
            jdbc.sql("""
                    UPDATE place_search_history SET query = :query, searched_at = :now WHERE id = :id
                    """).param("query", query).param("now", now.atOffset(ZoneOffset.UTC)).param("id", id).update();
        } else {
            jdbc.sql("""
                    INSERT INTO place_search_history (id, user_id, query, normalized_query, searched_at)
                    VALUES (:id, :user, :query, :normalized, :now)
                    """).param("id", id).param("user", userId).param("query", query)
                    .param("normalized", normalized).param("now", now.atOffset(ZoneOffset.UTC)).update();
        }
        jdbc.sql("""
                DELETE FROM place_search_history WHERE user_id = :user AND id IN (
                    SELECT id FROM place_search_history WHERE user_id = :user
                    ORDER BY searched_at DESC, id LIMIT 2147483647 OFFSET 30
                )
                """).param("user", userId).update();
        return new Entry(id, query, now);
    }

    record Entry(String id, String query, Instant searchedAt) {
    }
}
