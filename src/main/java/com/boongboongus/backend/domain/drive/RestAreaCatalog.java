package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RestAreaCatalog {
    private static final RowMapper<Area> AREA = (row, index) -> new Area(row.getString("id"),
            row.getString("name"), row.getString("highway_name"), row.getString("direction"),
            new Coordinate(row.getDouble("latitude"), row.getDouble("longitude")), row.getString("address"),
            row.getObject("travel_bearing", Double.class), row.getString("introduction"));

    private final JdbcClient jdbc;

    RestAreaCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    List<Area> all() {
        return jdbc.sql("SELECT * FROM rest_areas ORDER BY id").query(AREA).list();
    }

    Optional<Area> find(String id) {
        return jdbc.sql("SELECT * FROM rest_areas WHERE id = :id").param("id", id).query(AREA).optional();
    }

    List<Menu> menus(String id) {
        return jdbc.sql("SELECT name, image_url FROM rest_area_menus WHERE rest_area_id = :id ORDER BY position")
                .param("id", id).query((row, index) -> new Menu(row.getString("name"), row.getString("image_url")))
                .list();
    }

    Map<String, Instant> earned(String coupleId) {
        if (coupleId == null) {
            return Map.of();
        }
        return jdbc.sql("SELECT rest_area_id, earned_at FROM rest_area_stamps WHERE couple_id = :couple")
                .param("couple", coupleId).query((row, index) -> Map.entry(row.getString("rest_area_id"),
                        row.getObject("earned_at", OffsetDateTime.class).toInstant()))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    record Area(String id, String name, String highwayName, String direction, Coordinate coordinate,
            String address, Double travelBearing, String introduction) {
        Summary summary(Double routeDistance, Double searchDistance, Instant earnedAt) {
            return new Summary(id, name, highwayName, direction, coordinate, routeDistance,
                    earnedAt != null, earnedAt, address, searchDistance);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Summary(String id, String name, String highwayName, String direction, Coordinate coordinate,
            Double distanceAlongRouteMeters, boolean earned, Instant earnedAt, String address, Double distanceMeters) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Menu(String name, String imageUrl) {
    }
}