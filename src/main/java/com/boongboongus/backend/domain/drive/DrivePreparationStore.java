package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        return jdbc.sql("SELECT * FROM drives WHERE couple_id = :coupleId AND id = :id FOR UPDATE")
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
                readPlace(rs.getString("destination_snapshot")), rs.getInt("control_version"));
    }

    private Place readPlace(String snapshot) {
        return snapshot == null ? null : json.readValue(snapshot, Place.class);
    }

    record DriveRow(String id, String coupleId, String title, String status, String driverUserId,
            String passengerUserId, String recorderUserId, String routeId, Place origin, Place destination,
            int controlVersion) {

        DriveView view() {
            return view(status, controlVersion);
        }

        DriveView view(String viewStatus, int version) {
            return new DriveView(id, coupleId, title, viewStatus, driverUserId, passengerUserId, recorderUserId,
                    origin, destination, routeId, null, null, null, null, version,
                    new DriveView.Metrics(0, 0, 0, null, null, "MISSING"), false, null, null, null);
        }
    }

    record RequestKey(String coupleId, String userId, String key) {
    }

    record StoredRequest(String fingerprint, Instant expiresAt, String responseSnapshot) {
    }
}
