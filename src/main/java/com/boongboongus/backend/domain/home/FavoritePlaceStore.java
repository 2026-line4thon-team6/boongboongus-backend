package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class FavoritePlaceStore {
    private final JdbcClient jdbc;

    FavoritePlaceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean contains(String userId, String placeId) {
        return jdbc.sql("SELECT COUNT(*) FROM favorite_places WHERE user_id = :user AND place_id = :place")
                .param("user", userId).param("place", placeId).query(Long.class).single() > 0;
    }

    void save(String userId, PlaceCatalog.Place place) {
        jdbc.sql("""
                INSERT INTO favorite_places (user_id, place_id, category) VALUES (:user, :place, :category)
                ON CONFLICT DO NOTHING
                """).param("user", userId).param("place", place.id()).param("category", place.category().name())
                .update();
    }

    void remove(String userId, String placeId) {
        jdbc.sql("DELETE FROM favorite_places WHERE user_id = :user AND place_id = :place")
                .param("user", userId).param("place", placeId).update();
    }

    List<Entry> page(String userId, Category category, Entry after, int size) {
        String filter = category == null ? "" : " AND category = :category";
        String next = after == null ? "" : """
                 AND (saved_at < :savedAt OR (saved_at = :savedAt AND place_id > :place))
                """;
        var query = jdbc.sql("""
                SELECT place_id, saved_at FROM favorite_places WHERE user_id = :user
                """ + filter + next + " ORDER BY saved_at DESC, place_id LIMIT :size")
                .param("user", userId).param("size", size);
        if (category != null) {
            query.param("category", category.name());
        }
        if (after != null) {
            query.param("savedAt", after.savedAt()).param("place", after.placeId());
        }
        return query.query((row, index) -> new Entry(row.getString("place_id"),
                row.getObject("saved_at", OffsetDateTime.class))).list();
    }

    record Entry(String placeId, OffsetDateTime savedAt) {
    }
}
