package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.DrivePreparationStore.DriveRow;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Area;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class StampService {
    private static final Duration EVIDENCE_WINDOW = Duration.ofMinutes(15);
    private static final RowMapper<ClaimRow> CLAIM = (row, index) -> new ClaimRow(row.getString("id"),
            row.getString("couple_id"), row.getString("drive_id"), row.getString("rest_area_id"),
            row.getString("status"), row.getString("stamp_id"), row.getBoolean("already_earned"),
            row.getObject("created_at", OffsetDateTime.class).toInstant(),
            row.getObject("updated_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<StampRow> STAMP = (row, index) -> new StampRow(row.getString("id"),
            row.getString("first_drive_id"), row.getObject("earned_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;
    private final RestAreaCatalog catalog;
    private final DrivePreparationStore drives;
    private final IdempotentWrite idempotency;
    private final TransactionTemplate transactions;

    StampService(JdbcClient jdbc, RestAreaCatalog catalog, DrivePreparationStore drives,
            IdempotentWrite idempotency, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.drives = drives;
        this.idempotency = idempotency;
        this.transactions = transactions;
    }

    ClaimView claim(String userId, String coupleId, String driveId, String key, RestAreaController.ClaimRequest request) {
        return idempotency.run(userId, key, List.of("rest-area-stamp", coupleId, driveId, request), ClaimView.class,
                () -> create(coupleId, driveId, request.restAreaId()));
    }

    private ClaimView create(String coupleId, String driveId, String areaId) {
        DriveRow drive = drives.lockDrive(coupleId, driveId).orElseThrow(StampService::notFound);
        if (!List.of("ACTIVE", "PAUSED", "ENDED").contains(drive.status())) {
            throw new DriveApiException(HttpStatus.CONFLICT, "DRIVE_STATE_CONFLICT",
                    "주행을 시작한 뒤 휴게소 방문을 요청해 주세요.");
        }
        Area area = catalog.find(areaId).orElseThrow(StampService::notFound);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var earned = findStamp(coupleId, areaId);
        boolean hasEvidence = earned.isEmpty() && visited(area, drives.gpsHistory(driveId).values());
        Instant until = deadline(drive);
        if (earned.isEmpty() && !hasEvidence && until != null && now.isAfter(until)) {
            throw new DriveApiException(HttpStatus.UNPROCESSABLE_CONTENT, "STAMP_LOCATION_NOT_VERIFIED",
                    "방문 GPS를 확인할 수 있는 시간이 지났습니다.");
        }
        String id = "clm_" + UUID.randomUUID();
        var claim = new ClaimRow(id, coupleId, driveId, areaId, "PENDING", null, false, now, now);
        jdbc.sql("""
                INSERT INTO rest_area_stamp_claims (id, couple_id, drive_id, rest_area_id, status, created_at, updated_at)
                VALUES (:id, :couple, :drive, :area, 'PENDING', :at, :at)
                """).param("id", id).param("couple", coupleId).param("drive", driveId)
                .param("area", areaId).param("at", now.atOffset(ZoneOffset.UTC)).update();
        if (earned.isPresent()) {
            claim = settle(claim, "VERIFIED", earned.get().id(), true, now);
        } else if (hasEvidence) {
            claim = verify(claim, now);
        }
        return view(claim, area, drive);
    }

    ClaimView get(String coupleId, String claimId) {
        var claim = jdbc.sql("SELECT * FROM rest_area_stamp_claims WHERE id = :id AND couple_id = :couple")
                .param("id", claimId).param("couple", coupleId).query(CLAIM).optional().orElseThrow(StampService::notFound);
        return view(claim, catalog.find(claim.areaId()).orElseThrow(StampService::notFound),
                drives.findDrive(coupleId, claim.driveId()).orElseThrow(StampService::notFound));
    }

    StampBook book(String coupleId, String cursor, int limit) {
        var earned = catalog.earned(coupleId);
        var areas = catalog.all();
        var items = areas.stream().map(area -> area.summary(null, null, earned.get(area.id()))).toList();
        var page = RestAreaPage.slice(items, cursor, limit, "stamp-book-" + coupleId);
        return new StampBook(earned.size(), areas.size(), areas.size(), page.items(), page.nextCursor(), page.hasMore());
    }

    void resolvePending(DriveRow drive, Collection<DriveGps.Sample> evidence, Instant now) {
        var pending = jdbc.sql("""
                SELECT * FROM rest_area_stamp_claims WHERE drive_id = :drive AND status = 'PENDING'
                ORDER BY created_at, id
                """).param("drive", drive.id()).query(CLAIM).list();
        Instant until = deadline(drive);
        for (var claim : pending) {
            var prior = findStamp(claim.coupleId(), claim.areaId());
            if (prior.isPresent()) {
                settle(claim, "VERIFIED", prior.get().id(), true, now);
                continue;
            }
            Area area = catalog.find(claim.areaId()).orElseThrow(StampService::notFound);
            if (visited(area, evidence)) {
                verify(claim, now);
            } else if (until != null && now.isAfter(until)) {
                settle(claim, "REJECTED", null, false, now);
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    void expirePending() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var overdue = jdbc.sql("""
                SELECT DISTINCT c.drive_id, c.couple_id
                FROM rest_area_stamp_claims c JOIN drives d ON c.drive_id = d.id
                WHERE c.status = 'PENDING' AND d.ended_at < :cutoff
                """).param("cutoff", now.minus(EVIDENCE_WINDOW).atOffset(ZoneOffset.UTC))
                .query((row, index) -> new DriveKey(row.getString("drive_id"), row.getString("couple_id"))).list();
        for (var key : overdue) {
            transactions.executeWithoutResult(status -> drives.lockDrive(key.coupleId(), key.driveId())
                    .ifPresent(drive -> resolvePending(drive, drives.gpsHistory(drive.id()).values(), now)));
        }
    }

    private ClaimRow verify(ClaimRow claim, Instant now) {
        String newId = "stp_" + UUID.randomUUID();
        boolean inserted = jdbc.sql("""
                INSERT INTO rest_area_stamps (id, couple_id, rest_area_id, first_drive_id, earned_at)
                VALUES (:id, :couple, :area, :drive, :at) ON CONFLICT DO NOTHING
                """).param("id", newId).param("couple", claim.coupleId()).param("area", claim.areaId())
                .param("drive", claim.driveId()).param("at", now.atOffset(ZoneOffset.UTC)).update() == 1;
        String id = inserted ? newId : findStamp(claim.coupleId(), claim.areaId()).orElseThrow().id();
        return settle(claim, "VERIFIED", id, !inserted, now);
    }

    private ClaimRow settle(ClaimRow claim, String status, String stampId, boolean alreadyEarned, Instant now) {
        jdbc.sql("""
                UPDATE rest_area_stamp_claims SET status = :status, stamp_id = :stamp,
                    already_earned = :already, updated_at = :at
                WHERE id = :id AND status = 'PENDING'
                """).param("status", status).param("stamp", stampId).param("already", alreadyEarned)
                .param("at", now.atOffset(ZoneOffset.UTC)).param("id", claim.id()).update();
        return new ClaimRow(claim.id(), claim.coupleId(), claim.driveId(), claim.areaId(),
                status, stampId, alreadyEarned, claim.createdAt(), now);
    }

    private Optional<StampRow> findStamp(String coupleId, String areaId) {
        return jdbc.sql("SELECT * FROM rest_area_stamps WHERE couple_id = :couple AND rest_area_id = :area")
                .param("couple", coupleId).param("area", areaId).query(STAMP).optional();
    }

    private ClaimView view(ClaimRow claim, Area area, DriveRow drive) {
        boolean pending = "PENDING".equals(claim.status());
        boolean verified = "VERIFIED".equals(claim.status());
        StampView stamp = null;
        if (verified) {
            StampRow stored = jdbc.sql("SELECT * FROM rest_area_stamps WHERE id = :id AND couple_id = :couple")
                    .param("id", claim.stampId()).param("couple", claim.coupleId()).query(STAMP).single();
            stamp = new StampView(stored.id(), area.summary(null, null, stored.earnedAt()),
                    stored.earnedAt(), stored.firstDriveId());
        }
        Instant until = deadline(drive);
        String reason = pending ? "GPS 방문 증거를 확인하고 있어요."
                : verified ? null : "휴게소 방문 GPS 증거를 확인하지 못했어요.";
        return new ClaimView(claim.id(), claim.status(), reason, claim.alreadyEarned(), stamp, claim.createdAt(),
                claim.updatedAt(), until, pending && until != null ? 5 : null);
    }

    private static boolean visited(Area area, Collection<DriveGps.Sample> evidence) {
        return evidence.stream().anyMatch(sample -> sample.coordinate() != null
                && DriveGps.meters(sample.coordinate(), area.coordinate()) <= 200);
    }

    private static Instant deadline(DriveRow drive) {
        return drive.endedAt() == null ? null : drive.endedAt().plus(EVIDENCE_WINDOW);
    }

    private static DriveApiException notFound() {
        return new DriveApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 주행 또는 휴게소를 찾을 수 없습니다.");
    }

    private record ClaimRow(String id, String coupleId, String driveId, String areaId, String status,
            String stampId, boolean alreadyEarned, Instant createdAt, Instant updatedAt) {
    }

    private record StampRow(String id, String firstDriveId, Instant earnedAt) {
    }

    private record DriveKey(String driveId, String coupleId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ClaimView(String id, String status, String reason, boolean alreadyEarned, StampView stamp,
            Instant createdAt, Instant updatedAt, Instant recheckUntil, Integer retryAfterSeconds) {
    }

    record StampView(String id, Summary restArea, Instant earnedAt, String firstDriveId) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record StampBook(int earnedCount, int catalogCount, int capacity, List<Summary> items, String nextCursor,
            boolean hasMore) {
    }
}