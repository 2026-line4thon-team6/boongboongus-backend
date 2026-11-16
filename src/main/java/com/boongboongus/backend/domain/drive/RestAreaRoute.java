package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.LiveRoute;
import com.boongboongus.backend.domain.drive.RestAreaCatalog.Area;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class RestAreaRoute {
    private static final int MAX_AREA_OFFSET_METERS = 250;
    private static final int MAX_GPS_OFFSET_METERS = 150;
    private static final int MAX_DIRECTION_ERROR = 60;
    private static final double METERS_PER_DEGREE = 111_195;

    private final List<Segment> segments;

    RestAreaRoute(LiveRoute route) {
        double offset = 0;
        List<Segment> path = new ArrayList<>();
        for (var road : route.roads()) {
            for (int index = 1; index < road.vertices().size(); index++) {
                var from = road.vertices().get(index - 1);
                var to = road.vertices().get(index);
                double length = DriveGps.meters(from, to);
                if (length > 0) {
                    path.add(new Segment(from, to, normalizedRoad(road.name()), offset, length));
                    offset += length;
                }
            }
        }
        segments = List.copyOf(path);
    }

    Optional<Projection> position(Coordinate point) {
        return segments.stream().map(segment -> segment.project(point))
                .filter(projection -> projection.offsetMeters() <= MAX_GPS_OFFSET_METERS)
                .min(Comparator.comparingDouble(Projection::offsetMeters));
    }

    List<Match> areas(List<Area> catalog) {
        return catalog.stream().filter(area -> area.travelBearing() != null)
                .flatMap(area -> match(area).stream())
                .sorted(Comparator.comparingDouble(Match::meters).thenComparing(match -> match.area().id()))
                .toList();
    }

    private Optional<Match> match(Area area) {
        String road = normalizedRoad(area.highwayName());
        if (road.isEmpty()) {
            return Optional.empty();
        }
        // ponytail: scan the provider's segments for the curated catalog; add a spatial index if volume requires it.
        return segments.stream().filter(segment -> road.equals(segment.road()))
                .filter(segment -> angularDifference(segment.bearing(), area.travelBearing()) <= MAX_DIRECTION_ERROR)
                .map(segment -> segment.project(area.coordinate()))
                .filter(projection -> projection.insideSegment() && projection.offsetMeters() <= MAX_AREA_OFFSET_METERS)
                .min(Comparator.comparingDouble(Projection::offsetMeters))
                .map(projection -> new Match(area, projection.meters()));
    }

    private static String normalizedRoad(String value) {
        return value == null ? "" : value.replaceAll("[^\\p{L}\\p{N}]", "")
                .replaceFirst("(고속도로|선)$", "");
    }

    private static double angularDifference(double first, double second) {
        return Math.abs((first - second + 540) % 360 - 180);
    }

    record Match(Area area, double meters) {
    }

    record Projection(double meters, double offsetMeters, boolean insideSegment) {
    }

    private record Segment(Coordinate from, Coordinate to, String road, double start, double length) {
        double bearing() {
            double lng = (to.longitude() - from.longitude()) * Math.cos(Math.toRadians(from.latitude()));
            return (Math.toDegrees(Math.atan2(lng, to.latitude() - from.latitude())) + 360) % 360;
        }

        Projection project(Coordinate point) {
            double scale = METERS_PER_DEGREE * Math.cos(Math.toRadians(from.latitude()));
            double x = (to.longitude() - from.longitude()) * scale;
            double y = (to.latitude() - from.latitude()) * METERS_PER_DEGREE;
            double pointX = (point.longitude() - from.longitude()) * scale;
            double pointY = (point.latitude() - from.latitude()) * METERS_PER_DEGREE;
            double lengthSquared = x * x + y * y;
            double fraction = lengthSquared == 0 ? 0 : (pointX * x + pointY * y) / lengthSquared;
            double along = Math.clamp(fraction, 0, 1);
            return new Projection(start + along * length, Math.hypot(pointX - along * x, pointY - along * y),
                    fraction >= 0 && fraction <= 1);
        }
    }
}