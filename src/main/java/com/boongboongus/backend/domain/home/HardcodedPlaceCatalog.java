package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class HardcodedPlaceCatalog implements PlaceCatalog {
    // Address and coordinates: https://data.seoul.go.kr/dataList/OA-394/S/1/datasetView.do
    private static final Place SEOUL_FOREST = new Place(
            "plc_seoul_forest", "서울숲", "서울 성동구", "서울특별시 성동구 뚝섬로 273",
            new Coordinate(37.5430717, 127.0417982), null, Category.NATURE,
            List.of("산책", "공원"), List.of(), null, null, null, List.of());
    private static final List<Place> PLACES = List.of(SEOUL_FOREST);
    private static final List<Place> POPULAR_DESTINATIONS = List.of(SEOUL_FOREST);

    @Override
    public List<Place> findByCategory(Category category) {
        return PLACES.stream().filter(place -> place.category() == category).toList();
    }

    @Override
    public List<Place> findPopularDestinations() {
        return POPULAR_DESTINATIONS;
    }

    @Override
    public Optional<Place> findById(String placeId) {
        return PLACES.stream().filter(place -> placeId.equals(place.id())).findFirst();
    }
}
