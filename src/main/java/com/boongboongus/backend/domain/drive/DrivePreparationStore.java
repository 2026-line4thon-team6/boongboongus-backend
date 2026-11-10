package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Route;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
class DrivePreparationStore {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    DrivePreparationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    boolean insert(DriveRow drive) {
        return jdbc.sql("""
                INSERT INTO drives (
                    id, couple_id, title, status, active_slot, driver_user_id, passenger_user_id,
                    recorder_user_id, route_id, origin_snapshot, destination_snapshot, control_version
                ) VALUES (
                    :id, :coupleId, :title, 'PREPARING', 1, :driverId, :passengerId,
                    :recorderId, :routeId, :origin, :destination, 1
                )
                ON CONFLICT DO NOTHING
                """)
                .param("id", drive.id())
                .param("coupleId", drive.coupleId())
                .param("title", drive.title())
                .param("driverId", drive.driverUserId())
                .param("passengerId", drive.passengerUserId())
                .param("recorderId", drive.recorderUserId())
                .param("routeId", drive.routeId())
                .param("origin", drive.origin() == null ? null : json.writeValueAsString(drive.origin()))
                .param("destination", drive.destination() == null ? null : json.writeValueAsString(drive.destination()))
                .update() == 1;
    }

    Optional<DriveRow> findActive(String coupleId) {
        return jdbc.sql("SELECT * FROM drives WHERE couple_id = :coupleId AND active_slot = 1")
                .param("coupleId", coupleId)
                .query(this::readDrive)
                .optional();
    }

    Optional<DriveRow> lockDrive(String coupleId, String driveId) {
        return findDrive(coupleId, driveId, true);
    }

    Optional<DriveRow> findDrive(String coupleId, String driveId) {
        return findDrive(coupleId, driveId, false);
    }

    private Optional<DriveRow> findDrive(String coupleId, String driveId, boolean forUpdate) {
        String sql = "SELECT * FROM drives WHERE couple_id = :coupleId AND id = :id";
        return jdbc.sql(forUpdate ? sql + " FOR UPDATE" : sql)
                .param("coupleId", coupleId)
                .param("id", driveId)
                .query(this::readDrive)
                .optional();
    }

    void cancel(DriveRow drive) {
        jdbc.sql("""
                UPDATE drives SET status = 'CANCELLED', active_slot = NULL, control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .update();
    }

    void update(DriveRow drive, String title, String routeId, Route route) {
        jdbc.sql("""
                UPDATE drives SET title = :title, route_id = :routeId, origin_snapshot = :origin,
                    destination_snapshot = :destination, control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("title", title)
                .param("routeId", route == null ? drive.routeId() : routeId)
                .param("origin", snapshot(route == null ? drive.origin() : route.origin()))
                .param("destination", snapshot(route == null ? drive.destination() : route.destination()))
                .update();
    }

    void start(DriveRow drive, DriveControlRequest.InitialCoordinate coordinate, Instant now) {
        jdbc.sql("""
                UPDATE drives SET status = 'ACTIVE', started_at = :now, state_changed_at = :now,
                    initial_origin_snapshot = origin_snapshot, initial_coordinate = :coordinate,
                    control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("coordinate", snapshot(coordinate))
                .update();
    }

    void pause(DriveRow drive, Instant now) {
        updateClock(drive, "PAUSED", now);
        jdbc.sql("""
                INSERT INTO drive_pause_periods (drive_id, pause_version, paused_at) VALUES (:id, :version, :now)
                """)
                .param("id", drive.id())
                .param("version", drive.controlVersion() + 1)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    void resume(DriveRow drive, Instant now) {
        updateClock(drive, "ACTIVE", now);
        int resumed = jdbc.sql("""
                UPDATE drive_pause_periods SET resumed_at = :now WHERE drive_id = :id AND resumed_at IS NULL
                """)
                .param("id", drive.id())
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
        if (resumed != 1) {
            throw new IllegalStateException("Expected exactly one open pause period");
        }
    }

    private void updateClock(DriveRow drive, String status, Instant now) {
        jdbc.sql("""
                UPDATE drives SET status = :status, state_changed_at = :now,
                    active_millis = :activeMillis, paused_millis = :pausedMillis,
                    control_version = control_version + 1
                WHERE id = :id
                """)
                .param("id", drive.id())
                .param("status", status)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("activeMillis", drive.activeMillisAt(now))
                .param("pausedMillis", drive.pausedMillisAt(now))
                .update();
    }

    boolean reserve(RequestKey key, String fingerprint, Instant expiresAt) {
        return request("""
                INSERT INTO drive_idempotency (couple_id, user_id, idempotency_key, fingerprint, expires_at)
                VALUES (:coupleId, :userId, :key, :fingerprint, :expiresAt)
                ON CONFLICT DO NOTHING
                """, key)
                .param("fingerprint", fingerprint)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    StoredRequest lockRequest(RequestKey key) {
        return request("""
                SELECT fingerprint, expires_at, response_snapshot FROM drive_idempotency
                WHERE couple_id = :coupleId AND user_id = :userId AND idempotency_key = :key
                FOR UPDATE
                """, key)
                .query((rs, row) -> new StoredRequest(
                        rs.getString("fingerprint"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                        rs.getString("response_snapshot")))
                .single();
    }

    void saveResult(RequestKey key, String fingerprint, Instant expiresAt, DriveView view) {
        request("""
                UPDATE drive_idempotency
                SET fingerprint = :fingerprint, expires_at = :expiresAt, response_snapshot = :snapshot
                WHERE couple_id = :coupleId AND user_id = :userId AND idempotency_key = :key
                """, key)
                .param("fingerprint", fingerprint)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .param("snapshot", json.writeValueAsString(view))
                .update();
    }

    private JdbcClient.StatementSpec request(String sql, RequestKey key) {
        return jdbc.sql(sql)
                .param("coupleId", key.coupleId())
                .param("userId", key.userId())
                .param("key", key.key());
    }

    private DriveRow readDrive(ResultSet rs, int row) throws SQLException {
        return new DriveRow(
                rs.getString("id"), rs.getString("couple_id"), rs.getString("title"),
                rs.getString("status"), rs.getString("driver_user_id"), rs.getString("passenger_user_id"),
                rs.getString("recorder_user_id"), rs.getString("route_id"), readPlace(rs.getString("origin_snapshot")),
                readPlace(rs.getString("destination_snapshot")), rs.getInt("control_version"),
                instant(rs, "started_at"), instant(rs, "state_changed_at"),
                rs.getLong("active_millis"), rs.getLong("paused_millis"));
    }

    private String snapshot(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Place readPlace(String snapshot) {
        return snapshot == null ? null : json.readValue(snapshot, Place.class);
    }

    record DriveRow(String id, String coupleId, String title, String status, String driverUserId,
            String passengerUserId, String recorderUserId, String routeId, Place origin, Place destination,
            int controlVersion, Instant startedAt, Instant stateChangedAt, long activeMillis, long pausedMillis) {

        DriveView view() {
            return view(Instant.now());
        }

        DriveView view(Instant now) {
            Instant pausedAt = "PAUSED".equals(status) ? stateChangedAt : null;
            return new DriveView(id, coupleId, title, status, driverUserId, passengerUserId, recorderUserId,
                    origin, destination, routeId, startedAt, pausedAt, null, null, controlVersion,
                    new DriveView.Metrics(0, Math.toIntExact(activeMillisAt(now) / 1000),
                            Math.toIntExact(pausedMillisAt(now) / 1000), null, null, "MISSING"),
                    false, null, null, null);
        }

        long activeMillisAt(Instant now) {
            return activeMillis + elapsedWhile("ACTIVE", now);
        }

        long pausedMillisAt(Instant now) {
            return pausedMillis + elapsedWhile("PAUSED", now);
        }

        private long elapsedWhile(String phase, Instant now) {
            return phase.equals(status) && stateChangedAt != null
                    ? Math.max(0, Duration.between(stateChangedAt, now).toMillis()) : 0;
        }
    }

    record RequestKey(String coupleId, String userId, String key) {
    }

    record StoredRequest(String fingerprint, Instant expiresAt, String responseSnapshot) {
    }
}
