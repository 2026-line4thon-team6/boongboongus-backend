package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.boongboongus.backend.domain.home.HomeDataSource.PendingCard;
import com.boongboongus.backend.domain.playlist.repository.PlaylistRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class CardStore {
    private static final String CARD_SELECT = """
            SELECT c.*, d.couple_id, d.driver_user_id, d.passenger_user_id, d.started_at, d.destination_snapshot,
                d.distance_meters, d.active_millis,
                (SELECT COUNT(*) FROM rest_area_stamps s WHERE s.couple_id = d.couple_id
                    AND s.first_drive_id = d.id) AS stamp_count
            FROM drive_cards c JOIN drives d ON d.id = c.drive_id
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PlaylistRepository playlists;

    CardStore(JdbcClient jdbc, JsonMapper json, PlaylistRepository playlists) {
        this.jdbc = jdbc;
        this.json = json;
        this.playlists = playlists;
    }

    public void createForDrive(String driveId, String title, Instant endedAt) {
        String playlist = playlists.latestForDrive(driveId).map(value -> json.writeValueAsString(
                new CardView.Playlist(value.id(), value.title(), value.trackCount(), value.durationSeconds(),
                        value.tracks().stream().findFirst().map(track -> track.albumImageUrl()).orElse(null))))
                .orElse(null);
        jdbc.sql("""
                INSERT INTO drive_cards (id, drive_id, title, editable_until, playlist_snapshot)
                VALUES (:id, :drive, :title, :expires, :playlist) ON CONFLICT DO NOTHING
                """).param("id", "crd_" + UUID.randomUUID()).param("drive", driveId).param("title", title)
                .param("expires", utc(endedAt.plus(Duration.ofHours(24)))).param("playlist", playlist).update();
    }

    Optional<CardRow> find(String couple, String id, boolean byDrive) {
        String column = byDrive ? "c.drive_id" : "c.id";
        return jdbc.sql(CARD_SELECT + " WHERE d.couple_id = :couple AND " + column + " = :id")
                .param("couple", couple).param("id", id).query(this::mapCard).optional();
    }

    Optional<CardRow> lock(String couple, String cardId) {
        return jdbc.sql("SELECT c.id FROM drive_cards c WHERE c.id = :id AND EXISTS "
                + "(SELECT 1 FROM drives d WHERE d.id = c.drive_id AND d.couple_id = :couple) FOR UPDATE")
                .param("id", cardId).param("couple", couple).query(String.class).optional()
                .flatMap(id -> find(couple, id, false));
    }

    void delete(String cardId) {
        jdbc.sql("DELETE FROM drive_cards WHERE id = :id").param("id", cardId).update();
    }

    public CompletedTotals completedTotals(String coupleId) {
        return jdbc.sql("""
                SELECT COUNT(*) AS cards, COALESCE(SUM(d.distance_meters), 0) AS distance
                FROM drive_cards c JOIN drives d ON d.id = c.drive_id
                WHERE d.couple_id = :couple AND c.status = 'COMPLETED'
                """).param("couple", coupleId).query((row, index) -> new CompletedTotals(
                        row.getLong("cards"), Math.round(row.getDouble("distance")))).single();
    }

    public record CompletedTotals(long count, long distanceMeters) {
    }

    void updateDraft(CardRow card, String title) {
        jdbc.sql("""
                UPDATE drive_cards SET title = :title, version = version + 1,
                    status = CASE WHEN status = 'UNWRITTEN' THEN 'DRAFT' ELSE status END
                WHERE id = :id
                """).param("title", title).param("id", card.id()).update();
    }

    void markDraft(String cardId) {
        jdbc.sql("UPDATE drive_cards SET status = 'DRAFT' WHERE id = :id AND status = 'UNWRITTEN'")
                .param("id", cardId).update();
    }

    void complete(String cardId, Instant now) {
        jdbc.sql("""
                UPDATE drive_cards SET status = 'COMPLETED', completed_at = :now, version = version + 1
                WHERE id = :id
                """).param("now", utc(now)).param("id", cardId).update();
    }

    void queueCompletionPush(String cardId, String recipient, Instant now) {
        jdbc.sql("""
                INSERT INTO card_completion_push_events (card_id, recipient_user_id, created_at)
                VALUES (:card, :recipient, :now)
                """).param("card", cardId).param("recipient", recipient).param("now", utc(now)).update();
    }

    List<ReflectionRow> visibleReflections(String cardId, String userId) {
        return jdbc.sql("""
                SELECT * FROM card_reflections WHERE card_id = :id AND (author_id = :user OR submitted_at IS NOT NULL)
                ORDER BY author_id
                """).param("id", cardId).param("user", userId).query(CardStore::mapReflection).list();
    }

    Optional<ReflectionRow> reflection(String cardId, String userId) {
        return jdbc.sql("SELECT * FROM card_reflections WHERE card_id = :id AND author_id = :user")
                .param("id", cardId).param("user", userId).query(CardStore::mapReflection).optional();
    }

    boolean hasSubmittedReflection(String cardId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM card_reflections WHERE card_id = :id AND submitted_at IS NOT NULL)")
                .param("id", cardId).query(Boolean.class).single();
    }

    void saveReflection(String cardId, ReflectionRow value) {
        var fields = jdbc.sql(value.version() == 1 ? """
                INSERT INTO card_reflections (card_id, author_id, body, submitted_at, updated_at, version)
                VALUES (:id, :user, :body, :submitted, :now, :version)
                """ : """
                UPDATE card_reflections SET body = :body, submitted_at = :submitted, updated_at = :now,
                    version = :version WHERE card_id = :id AND author_id = :user
                """);
        fields.param("id", cardId).param("user", value.author()).param("body", value.text())
                .param("submitted", value.submittedAt() == null ? null : utc(value.submittedAt()))
                .param("now", utc(value.updatedAt())).param("version", value.version()).update();
    }

    public List<PendingCard> pending(String coupleId, String userId) {
        Instant now = Instant.now();
        return jdbc.sql("""
                SELECT c.id, c.drive_id, c.title, c.status, c.editable_until,
                    (c.version > 1 OR EXISTS (SELECT 1 FROM card_reflections r WHERE r.card_id = c.id
                        AND (r.author_id = :user OR r.submitted_at IS NOT NULL) AND r.body <> '')) AS has_content
                FROM drive_cards c JOIN drives d ON d.id = c.drive_id
                WHERE d.couple_id = :couple AND :user IN (d.driver_user_id, d.passenger_user_id)
                    AND c.status IN ('UNWRITTEN', 'DRAFT') AND c.editable_until > :now
                ORDER BY c.editable_until, c.id
                """).param("couple", coupleId).param("user", userId).param("now", utc(now))
                .query((r, n) -> new PendingCard(r.getString("id"), r.getString("drive_id"), r.getString("title"),
                        r.getString("status"), instant(r, "editable_until"), 0, r.getBoolean("has_content"))).list();
    }

    List<Expiring> expired(Instant now) {
        return jdbc.sql("""
                SELECT c.id, d.couple_id FROM drive_cards c JOIN drives d ON d.id = c.drive_id
                WHERE c.status IN ('UNWRITTEN', 'DRAFT') AND c.editable_until <= :now
                ORDER BY c.editable_until, c.id LIMIT 100
                """).param("now", utc(now)).query((r, n) -> new Expiring(r.getString("id"), r.getString("couple_id"))).list();
    }

    void expire(CardRow card) {
        jdbc.sql("DELETE FROM card_reflections WHERE card_id = :id").param("id", card.id()).update();
        jdbc.sql("UPDATE drive_cards SET status = 'EXPIRED', title = (SELECT title FROM drives WHERE id = :drive) WHERE id = :id")
                .param("drive", card.driveId()).param("id", card.id()).update();
    }

    private CardRow mapCard(ResultSet r, int row) throws SQLException {
        String destination = r.getString("destination_snapshot");
        String playlist = r.getString("playlist_snapshot");
        return new CardRow(r.getString("id"), r.getString("drive_id"), r.getString("couple_id"),
                r.getString("driver_user_id"), r.getString("passenger_user_id"), r.getString("title"),
                r.getString("status"), instant(r, "started_at"), instant(r, "editable_until"),
                instant(r, "completed_at"), destination == null ? null : json.readValue(destination, Place.class),
                r.getDouble("distance_meters"), Math.toIntExact(r.getLong("active_millis") / 1000),
                r.getInt("stamp_count"), playlist == null ? null : json.readValue(playlist, CardView.Playlist.class),
                r.getInt("version"));
    }

    private static ReflectionRow mapReflection(ResultSet r, int row) throws SQLException {
        return new ReflectionRow(r.getString("author_id"), r.getString("body"), instant(r, "submitted_at"),
                instant(r, "updated_at"), r.getInt("version"));
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime value = r.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    record CardRow(String id, String driveId, String coupleId, String driver, String passenger, String title,
            String status, Instant startedAt, Instant editableUntil, Instant completedAt, Place destination,
            double distance, int duration, int stamps, CardView.Playlist playlist, int version) {
        boolean completed() {
            return completedAt != null;
        }
    }

    record ReflectionRow(String author, String text, Instant submittedAt, Instant updatedAt, int version) {
    }

    record Expiring(String id, String coupleId) {
    }
}
