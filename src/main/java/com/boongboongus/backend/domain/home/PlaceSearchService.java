package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;
import static com.boongboongus.backend.domain.home.HomeExploreService.decodeToken;
import static com.boongboongus.backend.domain.home.HomeExploreService.encodeToken;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.home.HomeExploreService.PlaceSummary;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import com.boongboongus.backend.domain.home.PlaceCatalog.Place;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class PlaceSearchService {
    private final KakaoPlaceClient kakao;
    private final PlaceCatalog catalog;
    private final HomeExploreService home;

    PlaceSearchService(KakaoPlaceClient kakao, PlaceCatalog catalog, HomeExploreService home) {
        this.kakao = kakao;
        this.catalog = catalog;
        this.home = home;
    }

    SearchPage search(String userId, String query, Coordinate origin, String cursor, int limit) {
        String prefix = "search/" + hash(userId + "\n" + query + "\n" + origin) + "/";
        List<Place> curated = catalogMatches(query);
        int offset = offset(decodeToken(cursor, prefix), curated.size());
        List<SearchPlace> items = new ArrayList<>();
        while (offset < curated.size() && items.size() < limit) {
            items.add(SearchPlace.of(home.summarize(catalog, userId, curated.get(offset), origin)));
            offset++;
        }
        boolean hasMore = offset < curated.size();
        if (!hasMore) {
            int providerOffset = offset - curated.size();
            do {
                int page = providerOffset / KakaoPlaceClient.KEYWORD_PAGE_SIZE + 1;
                int from = providerOffset % KakaoPlaceClient.KEYWORD_PAGE_SIZE;
                var result = kakao.search(query, origin, page);
                var documents = result.documents();
                if (documents.isEmpty() || from >= documents.size()) {
                    hasMore = false;
                    break;
                }
                int to = Math.min(documents.size(), from + limit - items.size());
                for (int index = from; index < to; index++) {
                    items.add(summary(documents.get(index), origin));
                }
                hasMore = to < documents.size() || (!result.meta().end() && page < KakaoPlaceClient.MAX_PAGE);
                providerOffset = to < documents.size() ? providerOffset + to - from
                        : page * KakaoPlaceClient.KEYWORD_PAGE_SIZE;
            } while (hasMore && items.size() < limit);
            offset = curated.size() + providerOffset;
        }
        return new SearchPage(List.copyOf(items), hasMore ? encodeToken(prefix + offset) : null, hasMore);
    }

    LocationAddress reverseGeocode(Coordinate coordinate) {
        var candidate = kakao.address(coordinate).orElse(null);
        if (candidate == null) {
            return new LocationAddress(coordinate, null, null, false);
        }
        var road = candidate.roadAddress();
        var parcel = candidate.address();
        var address = road != null && text(road.name()) ? road : parcel;
        if (address == null || !text(address.name())) {
            return new LocationAddress(coordinate, null, null, false);
        }
        String region = Stream.of(address.region1(), address.region2()).filter(PlaceSearchService::text)
                .collect(Collectors.joining(" "));
        return new LocationAddress(coordinate, address.name(), region.isEmpty() ? null : region, true);
    }

    private List<Place> catalogMatches(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return Arrays.stream(Category.values()).flatMap(category -> catalog.findByCategory(category).stream())
                .filter(place -> Stream.of(place.name(), place.address(), place.region())
                        .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(needle)))
                .distinct().sorted(Comparator.comparing(Place::id)).toList();
    }

    private static SearchPlace summary(KakaoPlaceClient.KeywordPlace place, Coordinate origin) {
        String address = text(place.roadAddress()) ? place.roadAddress() : place.address();
        String region = text(address) ? Arrays.stream(address.strip().split("\\s+")).limit(2)
                .collect(Collectors.joining(" ")) : null;
        if (!text(place.id()) || !text(place.name())) {
            throw providerError();
        }
        try {
            Coordinate location = HomeExploreController.coordinate(
                    Double.parseDouble(place.y()), Double.parseDouble(place.x()));
            Double distance = origin != null && text(place.distance()) ? Double.valueOf(place.distance()) : null;
            if (distance != null && (!Double.isFinite(distance) || distance < 0)) {
                throw providerError();
            }
            return new SearchPlace("plc_k_" + hash("KAKAO:" + place.id()), place.name(), region,
                    text(address) ? address : null, location, null, null, List.of(), null, distance, false, false);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw providerError();
        } catch (HomeExploreException exception) {
            if (exception.status == HttpStatus.BAD_REQUEST) {
                throw providerError();
            }
            throw exception;
        }
    }

    private static HomeExploreException providerError() {
        return new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "카카오맵 장소 정보를 확인할 수 없습니다.");
    }

    private static int offset(String value, int curatedSize) {
        if (value == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(value);
            if (offset > 0 && offset < curatedSize + KakaoPlaceClient.MAX_PAGE * KakaoPlaceClient.KEYWORD_PAGE_SIZE) {
                return offset;
            }
        } catch (NumberFormatException exception) {
            // All malformed cursors use the same client error.
        }
        throw badRequest("유효한 cursor를 보내 주세요.");
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SearchPlace(String id, String name, String region, String address, Coordinate coordinate, String thumbnailUrl,
            Category category, List<String> hashtags, Integer estimatedDurationSeconds, Double distanceMeters,
            boolean isFavorite, boolean canSave) {
        static SearchPlace of(PlaceSummary place) {
            return new SearchPlace(place.id(), place.name(), place.region(), place.address(), place.coordinate(),
                    place.thumbnailUrl(), place.category(), place.hashtags(), place.estimatedDurationSeconds(),
                    place.distanceMeters(), place.isFavorite(), true);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record SearchPage(List<SearchPlace> items, String nextCursor, boolean hasMore) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record LocationAddress(Coordinate coordinate, String address, String region, boolean available) {
    }
}
