package com.boongboongus.backend.domain.home;

import static com.boongboongus.backend.domain.home.HomeExploreException.badRequest;
import static com.boongboongus.backend.domain.home.HomeExploreService.decodeToken;
import static com.boongboongus.backend.domain.home.HomeExploreService.encodeToken;
import static com.boongboongus.backend.domain.home.HomeExploreService.notFound;
import static com.boongboongus.backend.domain.home.HomeExploreService.requiredSource;

import com.boongboongus.backend.common.api.IdempotentWrite;
import com.boongboongus.backend.domain.drive.CurrentCoupleLookup;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.home.HomeExploreService.PlaceSummary;
import com.boongboongus.backend.domain.home.PlaceCatalog.Category;
import com.boongboongus.backend.domain.home.PlaceCatalog.Place;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
class PlaceSharingService {
    private final ObjectProvider<PlaceCatalog> catalogs;
    private final HomeExploreService home;
    private final FavoritePlaceStore favorites;
    private final ObjectProvider<CurrentCoupleLookup> couples;
    private final ObjectProvider<PlaceShareNotification> notifications;
    private final IdempotentWrite idempotency;
    private final URI shareBase;

    PlaceSharingService(ObjectProvider<PlaceCatalog> catalogs, HomeExploreService home, FavoritePlaceStore favorites,
            ObjectProvider<CurrentCoupleLookup> couples, ObjectProvider<PlaceShareNotification> notifications,
            IdempotentWrite idempotency, @Value("${app.places.share-base-url:${PLACE_SHARE_BASE_URL:}}") String baseUrl) {
        this.catalogs = catalogs;
        this.home = home;
        this.favorites = favorites;
        this.couples = couples;
        this.notifications = notifications;
        this.idempotency = idempotency;
        this.shareBase = baseUrl.isBlank() ? null : validateShareBase(baseUrl);
    }

    HomeExploreService.PopularPage favorites(String userId, Category category, String cursor, int limit) {
        PlaceCatalog catalog = catalog();
        String prefix = "favorites/" + (category == null ? "all" : category.name()) + "/";
        FavoritePlaceStore.Entry after = favoriteCursor(decodeToken(cursor, prefix));
        FavoritePlaceStore.Entry lastShown = null;
        List<PlaceSummary> items = new ArrayList<>();
        boolean hasMore = false;
        do {
            var rows = favorites.page(userId, category, after, limit + 1);
            for (var row : rows) {
                var place = catalog.findById(row.placeId());
                if (place.isEmpty()) {
                    continue;
                }
                if (items.size() == limit) {
                    hasMore = true;
                    break;
                }
                items.add(home.summarize(catalog, userId, place.get(), null));
                lastShown = row;
            }
            after = rows.size() <= limit || hasMore ? null : rows.getLast();
        } while (after != null);
        String next = hasMore ? encodeToken(prefix + lastShown.savedAt() + "/" + lastShown.placeId()) : null;
        return new HomeExploreService.PopularPage(List.copyOf(items), next, hasMore);
    }

    void favorite(String userId, String placeId) {
        favorites.save(userId, requirePlace(placeId));
    }

    void unfavorite(String userId, String placeId) {
        favorites.remove(userId, placeId);
    }

    ShareLink shareLink(String placeId) {
        requirePlace(placeId);
        if (shareBase == null) {
            throw new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "공개 장소 링크를 사용할 수 없습니다.");
        }
        String url = UriComponentsBuilder.fromUri(shareBase).pathSegment("places", "{placeId}")
                .encode().buildAndExpand(placeId).toUriString();
        return new ShareLink(url, "PUBLIC_PLACE", null);
    }

    PublicPlace publicPlace(String placeId) {
        Place place = requirePlace(placeId);
        return new PublicPlace(place.id(), place.name(), place.address(), place.coordinate(), place.thumbnailUrl(),
                place.introduction());
    }

    SentPlace send(String userId, String expectedCoupleId, String key, String placeId) {
        var couple = requiredSource(couples, "커플 정보를 확인할 수 없습니다.").findCurrent(userId)
                .orElseThrow(() -> forbidden("COUPLE_REQUIRED", "현재 연결된 커플이 필요합니다."));
        if (!expectedCoupleId.equals(couple.id())) {
            throw forbidden("COUPLE_CONTEXT_CHANGED", "현재 커플 정보를 다시 확인해 주세요.");
        }
        String recipient;
        if (userId.equals(couple.firstMemberId())) {
            recipient = couple.secondMemberId();
        } else if (userId.equals(couple.secondMemberId())) {
            recipient = couple.firstMemberId();
        } else {
            throw forbidden("PERMISSION_DENIED", "이 커플에 접근할 수 없습니다.");
        }
        if (recipient == null || recipient.isBlank() || recipient.equals(userId)) {
            throw forbidden("COUPLE_REQUIRED", "현재 연결된 상대가 필요합니다.");
        }
        return idempotency.run(userId, key, List.of("send-place", expectedCoupleId, recipient, placeId), SentPlace.class,
                () -> {
                    requirePlace(placeId);
                    var receipt = requiredSource(notifications, "장소 공유 알림을 사용할 수 없습니다.")
                            .create(couple.id(), userId, recipient, placeId);
                    return new SentPlace(receipt.notificationId(), recipient, receipt.sentAt());
                });
    }

    private PlaceCatalog catalog() {
        return requiredSource(catalogs, "장소 정보를 확인할 수 없습니다.");
    }

    private Place requirePlace(String placeId) {
        return catalog().findById(placeId).orElseThrow(() -> notFound("장소를 찾을 수 없습니다."));
    }

    private static URI validateShareBase(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("app.places.share-base-url requires a public HTTPS base URL");
        }
        return uri;
    }

    private static FavoritePlaceStore.Entry favoriteCursor(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('/');
        if (separator < 1) {
            throw badRequest("유효한 cursor를 보내 주세요.");
        }
        try {
            String id = value.substring(separator + 1);
            PlaceRequests.validateId(id);
            return new FavoritePlaceStore.Entry(id, OffsetDateTime.parse(value.substring(0, separator)));
        } catch (DateTimeParseException exception) {
            throw badRequest("유효한 cursor를 보내 주세요.");
        }
    }

    private static HomeExploreException forbidden(String code, String message) {
        return new HomeExploreException(HttpStatus.FORBIDDEN, code, message);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ShareLink(String url, String access, Instant expiresAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record PublicPlace(String id, String name, String address, Coordinate coordinate, String thumbnailUrl,
            String introduction) {
    }

    record SentPlace(String notificationId, String recipientUserId, Instant sentAt) {
    }
}
