package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class RecordStore {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String COMPLETED = """
            FROM drive_cards c JOIN drives d ON d.id = c.drive_id
            WHERE c.status = 'COMPLETED' AND d.couple_id = :couple
                AND :user IN (d.driver_user_id, d.passenger_user_id)
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    RecordStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    long count(String couple, String user) {
        return scoped("SELECT COUNT(*) " + COMPLETED, couple, user).query(Long.class).single();
    }

    boolean canShare(String couple, String user, String cardId) {
        return scoped("SELECT EXISTS (SELECT 1 " + COMPLETED + " AND c.id = :id)", couple, user)
                .param("id", cardId).query(Boolean.class).single();
    }

    List<Row> find(String couple, String user, LocalDate from, LocalDate before) {
        // ponytail: filter dates and tenancy in SQL, then group existing destination snapshots in Java.
        // Move grouping/search to SQL with indexed destination fields if a couple's history grows large.
        return inDates("""
                SELECT c.id, d.id AS drive_id, c.title, d.started_at, d.destination_snapshot,
                    d.distance_meters, d.active_millis, c.completed_at
                """, couple, user, from, before).query((r, index) -> {
                    String destination = r.getString("destination_snapshot");
                    return new Row(r.getString("id"), r.getString("drive_id"), r.getString("title"),
                            date(r.getObject("started_at", OffsetDateTime.class)),
                            destination == null ? null : json.readValue(destination, Place.class),
                            r.getDouble("distance_meters"), Math.toIntExact(r.getLong("active_millis") / 1000),
                            r.getObject("completed_at", OffsetDateTime.class).toInstant());
                }).list();
    }

    List<LocalDate> dates(String couple, String user, LocalDate from, LocalDate before) {
        return inDates("SELECT d.started_at ", couple, user, from, before)
                .query((r, index) -> date(r.getObject("started_at", OffsetDateTime.class))).list();
    }

    private JdbcClient.StatementSpec inDates(String select, String couple, String user, LocalDate from, LocalDate before) {
        String sql = select + COMPLETED;
        if (from != null) {
            sql += " AND d.started_at >= :from";
        }
        if (before != null) {
            sql += " AND d.started_at < :before";
        }
        var statement = scoped(sql, couple, user);
        if (from != null) {
            statement.param("from", from.atStartOfDay(SEOUL).toOffsetDateTime());
        }
        if (before != null) {
            statement.param("before", before.atStartOfDay(SEOUL).toOffsetDateTime());
        }
        return statement;
    }

    private JdbcClient.StatementSpec scoped(String sql, String couple, String user) {
        return jdbc.sql(sql).param("couple", couple).param("user", user);
    }

    private static LocalDate date(OffsetDateTime time) {
        return time.atZoneSameInstant(SEOUL).toLocalDate();
    }

    record Row(String cardId, String driveId, String title, LocalDate date, Place destination,
            double distanceMeters, int activeDurationSeconds, Instant completedAt) {
    }
}
