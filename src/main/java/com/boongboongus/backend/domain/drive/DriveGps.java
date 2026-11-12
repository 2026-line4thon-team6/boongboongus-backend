package com.boongboongus.backend.domain.drive;

import com.boongboongus.backend.domain.drive.DrivePreparationStore.DriveRow;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

final class DriveGps {
    private static final BigDecimal MAX_ACCURACY = BigDecimal.valueOf(50);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);
    private static final double MAX_SPEED_METERS_PER_SECOND = 100;

    private DriveGps() {
    }

    record Sample(int sequence, Instant recordedAt, Coordinate coordinate) {
    }

    record Pause(Instant pausedAt, Instant resumedAt) {
    }

    record Rejection(int sequence, String reason) {
    }

    record Receipt(List<Integer> acceptedSequences, List<Integer> duplicateSequences, List<Rejection> rejected,
            int highestContiguousSequence, boolean arrivalCandidate, DriveView drive, List<Object> restAreaAlerts) {
    }

    record Summary(double distanceMeters, Instant lastGpsAt, String gpsQuality, int highestContiguousSequence,
            boolean arrivalCandidate) {
    }

    static String rejection(DriveGpsRequest.Sample input, Sample sample, NavigableMap<Integer, Sample> history,
            DriveRow drive, List<Pause> pauses, Instant now) {
        var position = input.coordinate();
        if (position.latitude().abs().compareTo(MAX_LATITUDE) > 0
                || position.longitude().abs().compareTo(MAX_LONGITUDE) > 0) {
            return "INVALID_COORDINATE";
        }
        Instant recordedAt = sample.recordedAt();
        if (recordedAt.isBefore(drive.startedAt()) || recordedAt.isAfter(now)
                || (drive.endedAt() != null && !recordedAt.isBefore(drive.endedAt()))
                || interval(recordedAt, pauses) < 0) {
            return "OUTSIDE_RECORDING_INTERVAL";
        }
        var before = history.lowerEntry(sample.sequence());
        var after = history.higherEntry(sample.sequence());
        if ((before != null && !recordedAt.isAfter(before.getValue().recordedAt()))
                || (after != null && !recordedAt.isBefore(after.getValue().recordedAt()))) {
            return "OUT_OF_ORDER_TIME";
        }
        if (input.accuracyMeters().compareTo(MAX_ACCURACY) > 0) {
            return "ACCURACY_TOO_LOW";
        }
        if ((input.speedMetersPerSecond() != null
                && input.speedMetersPerSecond().doubleValue() > MAX_SPEED_METERS_PER_SECOND)
                || impossibleSpeed(accurateNeighbor(history, before, true), sample, pauses)
                || impossibleSpeed(accurateNeighbor(history, after, false), sample, pauses)) {
            return "IMPOSSIBLE_SPEED";
        }
        return null;
    }

    static Sample from(DriveGpsRequest.Sample sample) {
        return new Sample(sample.sequence(), sample.recordedAt().truncatedTo(ChronoUnit.MICROS),
                new Coordinate(sample.coordinate().latitude().doubleValue(), sample.coordinate().longitude().doubleValue()));
    }

    static Summary summarize(NavigableMap<Integer, Sample> history, Coordinate destination, List<Pause> pauses) {
        double distance = 0;
        int contiguous = 0;
        int previousInterval = -1;
        Sample previous = null;
        Instant arrivalSince = null;
        Instant lastGpsAt = null;
        boolean arrival = false;
        for (Sample sample : history.values()) {
            if (sample.coordinate() == null) {
                arrivalSince = null;
                arrival = false;
                continue;
            }
            if (sample.sequence() == contiguous + 1) {
                contiguous++;
            }
            int interval = interval(sample.recordedAt(), pauses);
            boolean sameInterval = previous != null && interval == previousInterval;
            if (sameInterval) {
                distance += meters(previous.coordinate(), sample.coordinate());
            }
            if (destination == null || meters(sample.coordinate(), destination) > 100) {
                arrivalSince = null;
            } else if (arrivalSince == null || !sameInterval || sample.sequence() != previous.sequence() + 1
                    || Duration.between(previous.recordedAt(), sample.recordedAt()).compareTo(Duration.ofSeconds(15)) > 0) {
                arrivalSince = sample.recordedAt();
            }
            arrival = arrivalSince != null && Duration.between(arrivalSince, sample.recordedAt()).getSeconds() >= 30;
            previous = sample;
            previousInterval = interval;
            lastGpsAt = sample.recordedAt();
        }
        String quality = history.isEmpty() ? "MISSING"
                : history.lastEntry().getValue().coordinate() == null ? "DEGRADED" : "OK";
        return new Summary(distance, lastGpsAt, quality, contiguous, arrival);
    }

    private static Sample accurateNeighbor(NavigableMap<Integer, Sample> history, Map.Entry<Integer, Sample> entry,
            boolean earlier) {
        while (entry != null && entry.getValue().coordinate() == null) {
            entry = earlier ? history.lowerEntry(entry.getKey()) : history.higherEntry(entry.getKey());
        }
        return entry == null ? null : entry.getValue();
    }

    private static boolean impossibleSpeed(Sample neighbor, Sample sample, List<Pause> pauses) {
        if (neighbor == null || interval(neighbor.recordedAt(), pauses) != interval(sample.recordedAt(), pauses)) {
            return false;
        }
        Duration elapsed = Duration.between(neighbor.recordedAt(), sample.recordedAt()).abs();
        double seconds = elapsed.getSeconds() + elapsed.getNano() / 1_000_000_000d;
        return meters(neighbor.coordinate(), sample.coordinate()) > MAX_SPEED_METERS_PER_SECOND * seconds;
    }

    private static int interval(Instant time, List<Pause> pauses) {
        int interval = 0;
        for (Pause pause : pauses) {
            if (time.isBefore(pause.pausedAt())) {
                break;
            }
            if (pause.resumedAt() == null || time.isBefore(pause.resumedAt())) {
                return -1;
            }
            interval++;
        }
        return interval;
    }

    private static double meters(Coordinate from, Coordinate to) {
        double lat = Math.toRadians(to.latitude() - from.latitude());
        double lon = Math.toRadians(to.longitude() - from.longitude());
        double sinLat = Math.sin(lat / 2);
        double sinLon = Math.sin(lon / 2);
        double haversine = sinLat * sinLat + Math.cos(Math.toRadians(from.latitude()))
                * Math.cos(Math.toRadians(to.latitude())) * sinLon * sinLon;
        return 12_742_000 * Math.asin(Math.sqrt(Math.clamp(haversine, 0, 1)));
    }
}
