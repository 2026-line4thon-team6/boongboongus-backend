package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DrivePreparationStore.DriveRow;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.LiveRoute;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Area;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Menu;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Summary;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class RestAreaService {
    private static final int APPROACH_METERS = 2000;
    private final RestAreaCatalog catalog;
    private final DrivePreparationStore drives;
    private final ObjectProvider<DriveRouteLookup> routes;
    private final JdbcClient jdbc;

    RestAreaService(RestAreaCatalog catalog, DrivePreparationStore drives,
            ObjectProvider<DriveRouteLookup> routes, JdbcClient jdbc) {
        this.catalog = catalog;
        this.drives = drives;
        this.routes = routes;
        this.jdbc = jdbc;
    }

    RestAreaPage search(String coupleId, String query, Coordinate location, String cursor, int limit) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.length() > 100) {
            throw new DriveApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "검색어는 100자 이하여야 합니다.");
        }
        var earned = catalog.earned(coupleId);
        var items = catalog.all().stream()
                .filter(area -> area.name().toLowerCase(Locale.ROOT).contains(needle))
                .map(area -> area.summary(null,
                        location == null ? null : DriveGps.meters(location, area.coordinate()), earned.get(area.id())))
                .sorted(location == null ? Comparator.comparing(Summary::id)
                        : Comparator.comparing(Summary::distanceMeters).thenComparing(Summary::id))
                .toList();
        String scope = "rest-search-" + java.util.Objects.hash(coupleId, needle, location);
        return RestAreaPage.slice(items, cursor, limit, scope);
    }

    Detail detail(String coupleId, String id) {
        Area area = catalog.find(id).orElseThrow(() -> new DriveApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "휴게소를 찾을 수 없습니다."));
        var menus = catalog.menus(id);
        return new Detail(area.summary(null, null, catalog.earned(coupleId).get(id)), area.introduction(),
                menus, !menus.isEmpty());
    }

    RouteAreas alongDrive(String userId, String coupleId, String driveId, String cursor, int limit) {
        DriveRow drive = drives.findDrive(coupleId, driveId).orElseThrow(() ->
                new DriveApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "주행을 찾을 수 없습니다."));
        if (drive.routeId() == null) {
            var page = RestAreaPage.slice(List.of(), cursor, limit, "drive-" + driveId);
            return new RouteAreas(null, null, page.items(), page.nextCursor(), page.hasMore());
        }
        LiveRoute live = liveRoute(userId, drive);
        var route = new RestAreaRoute(live);
        Coordinate gps = latestGps(drives.gpsHistory(driveId));
        Double progress = gps == null ? Double.valueOf(0)
                : route.position(gps).map(RestAreaRoute.Projection::meters).orElse(null);
        var earned = catalog.earned(coupleId);
        var items = route.areas(catalog.all()).stream()
                .filter(match -> progress == null || match.meters() >= progress)
                .map(match -> match.area().summary(progress == null ? null : match.meters() - progress,
                        null, earned.get(match.area().id())))
                .toList();
        var page = RestAreaPage.slice(items, cursor, limit, "drive-" + driveId + "-" + live.polyline().hashCode());
        return new RouteAreas(live.routeId(), live.polyline(), page.items(), page.nextCursor(), page.hasMore());
    }

    List<Alert> approachAlerts(DriveRow drive, Coordinate gps, Instant now) {
        if (drive.routeId() == null || gps == null || !"ACTIVE".equals(drive.status())) {
            return List.of();
        }
        var candidates = catalog.all().stream().filter(area -> area.travelBearing() != null
                && DriveGps.meters(gps, area.coordinate()) <= APPROACH_METERS + 250).toList();
        if (candidates.isEmpty()) {
            return List.of();
        }
        var alreadySent = jdbc.sql("SELECT rest_area_id FROM drive_rest_area_alerts WHERE drive_id = :drive")
                .param("drive", drive.id()).query(String.class).set();
        var unsent = candidates.stream().filter(area -> !alreadySent.contains(area.id())).toList();
        if (unsent.isEmpty()) {
            return List.of();
        }
        RestAreaRoute route;
        try {
            route = new RestAreaRoute(liveRoute(drive.recorderUserId(), drive));
        } catch (DriveApiException exception) {
            // GPS persistence does not depend on a live response from the map provider.
            return List.of();
        }
        var progress = route.position(gps);
        if (progress.isEmpty()) {
            return List.of();
        }
        List<Alert> alerts = new ArrayList<>();
        for (var match : route.areas(unsent)) {
            double remaining = match.meters() - progress.get().meters();
            if (remaining < 0 || remaining > APPROACH_METERS) {
                continue;
            }
            boolean inserted = jdbc.sql("""
                    INSERT INTO drive_rest_area_alerts (drive_id, rest_area_id)
                    VALUES (:drive, :area) ON CONFLICT DO NOTHING
                    """).param("drive", drive.id()).param("area", match.area().id()).update() == 1;
            if (inserted) {
                alerts.add(new Alert("alert_" + UUID.randomUUID(), match.area().id(), match.area().name(),
                        Math.round(remaining), "전방에 " + match.area().name() + "가 있어요.", now));
            }
        }
        return List.copyOf(alerts);
    }

    private LiveRoute liveRoute(String userId, DriveRow drive) {
        DriveRouteLookup source = routes.getIfAvailable();
        if (source == null) {
            throw routeUnavailable();
        }
        try {
            return source.findLiveForDrive(userId, drive.coupleId(), drive.routeId()).orElseThrow(
                    RestAreaService::routeUnavailable);
        } catch (DriveRouteLookup.LiveRouteUnavailable exception) {
            throw routeUnavailable();
        }
    }

    private static DriveApiException routeUnavailable() {
        return new DriveApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "주행 경로를 조회할 수 없습니다.");
    }

    static Coordinate latestGps(NavigableMap<Integer, DriveGps.Sample> history) {
        return history.descendingMap().values().stream().map(DriveGps.Sample::coordinate)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Detail(Summary restArea, String introduction, List<Menu> menus, boolean detailsAvailable) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record RouteAreas(String routeId, String polyline, List<Summary> items, String nextCursor, boolean hasMore) {
    }

    record Alert(String id, String restAreaId, String restAreaName, long distanceMeters, String message,
            Instant triggeredAt) {
    }
}