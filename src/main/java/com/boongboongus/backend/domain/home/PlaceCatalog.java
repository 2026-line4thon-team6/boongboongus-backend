package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.util.List;
import java.util.Optional;

/** Supplies stable internal place IDs and the ranked popular destinations agreed by the team. */
public interface PlaceCatalog {
    List<Place> findByCategory(Category category);

    List<Place> findPopularDestinations();

    Optional<Place> findById(String placeId);

    default boolean isFavorite(String userId, String placeId) {
        return false;
    }

    enum Category {
        SEA("바다"),
        NIGHT_VIEW("야경"),
        NEARBY_CAFE("근교 카페"),
        NATURE("자연"),
        CAR_CAMPING("차박");

        final String label;

        Category(String label) {
            this.label = label;
        }
    }

    record Place(String id, String name, String region, String address, Coordinate coordinate, String thumbnailUrl,
            Category category, List<String> hashtags, List<String> photos, String introduction, String parkingInfo,
            String recommendedVisitTime, List<String> mainRoads) {
    }
}
