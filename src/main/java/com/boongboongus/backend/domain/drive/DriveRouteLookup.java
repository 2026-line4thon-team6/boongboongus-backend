package com.boongboongus.backend.domain.drive;

import java.util.Optional;

/** Finds an unexpired route the authenticated user can attach to their current couple's drive. */
public interface DriveRouteLookup {
    Optional<Route> findAccessible(String userId, String coupleId, String routeId);

    record Route(Place origin, Place destination) {
    }

    record Place(String placeId, String name, String address, Coordinate coordinate) {
    }

    record Coordinate(double latitude, double longitude) {
    }
}
