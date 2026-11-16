package com.boongboongus.backend.domain.card;

import com.boongboongus.backend.domain.card.CardService.CardError;
import com.boongboongus.backend.domain.card.RecordStore.Row;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.boongboongus.backend.domain.media.MediaService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
class RecordService {
    private static final Comparator<Position> ORDER = Comparator.comparing(Position::date)
            .thenComparing(Position::id).reversed();
    private final RecordStore records;
    private final CardService cards;
    private final MediaService media;
    private final URI shareBase;

    RecordService(RecordStore records, CardService cards, MediaService media,
            @Value("${CARD_SHARE_BASE_URL:}") String shareBase) {
        this.records = records;
        this.cards = cards;
        this.media = media;
        this.shareBase = shareBase.isBlank() ? null : parseShareBase(shareBase);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    Records list(Principal principal, Query query) {
        var actor = cards.actor(principal, null);
        Filter filter = Filter.parse(query);
        String scope = fingerprint("records", actor.couple(), actor.user(), filter, null);
        Position cursor = decode(query.cursor(), scope);
        List<Row> matches = matching(actor, filter);
        Page<Row> page = page(matches, Function.identity(), cursor, query.limit(), scope);
        var thumbnails = thumbnails(page.items(), actor.user());
        return new Records(records.count(actor.couple(), actor.user()), matches.size(),
                page.items().stream().map(row -> summary(row, thumbnails)).toList(), page.nextCursor(), page.hasMore());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    RecordMap map(Principal principal, Query query, String bbox) {
        var actor = cards.actor(principal, null);
        Filter filter = Filter.parse(query);
        Bounds bounds = Bounds.parse(bbox);
        String scope = fingerprint("map", actor.couple(), actor.user(), filter, bounds);
        Position cursor = decode(query.cursor(), scope);
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Row row : matching(actor, filter)) {
            Place destination = row.destination();
            if (destination == null || destination.coordinate() == null || !bounds.contains(destination.coordinate())) {
                continue;
            }
            String key = groupKey(destination);
            groups.computeIfAbsent(key, ignored -> new Group(key, row)).count++;
        }
        var page = page(List.copyOf(groups.values()), group -> group.latest, cursor, query.limit(), scope);
        var thumbnails = thumbnails(page.items().stream().map(group -> group.latest).toList(), actor.user());
        var markers = page.items().stream().map(group -> new Marker(group.key, group.latest.destination().coordinate(),
                summary(group.latest, thumbnails), group.count)).toList();
        long totalCount = groups.values().stream().mapToLong(group -> group.count).sum();
        return new RecordMap(totalCount, markers, page.nextCursor(), page.hasMore());
    }

    Calendar calendar(Principal principal, String month) {
        var actor = cards.actor(principal, null);
        YearMonth parsed;
        try {
            if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
                throw invalid("조회할 월을 확인해 주세요.");
            }
            parsed = YearMonth.parse(month);
            if (parsed.getYear() < 1) {
                throw invalid("조회할 월을 확인해 주세요.");
            }
        } catch (DateTimeParseException exception) {
            throw invalid("조회할 월을 확인해 주세요.");
        }
        var totals = records.dates(actor.couple(), actor.user(), parsed.atDay(1), parsed.plusMonths(1).atDay(1))
                .stream().collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
        return new Calendar(month, totals.entrySet().stream()
                .map(day -> new Day(day.getKey(), day.getValue())).toList());
    }

    ShareLink share(Principal principal, String cardId) {
        var actor = cards.actor(principal, null);
        if (!records.canShare(actor.couple(), actor.user(), cardId)) {
            throw CardError.notFound();
        }
        if (shareBase == null) {
            throw new CardError(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "카드 공유 주소를 확인할 수 없습니다.");
        }
        String url = UriComponentsBuilder.fromUri(shareBase).pathSegment("cards", cardId)
                .build().encode().toUriString();
        return new ShareLink(url, "COUPLE_ONLY", null);
    }

    private List<Row> matching(CardService.Actor actor, Filter filter) {
        return records.find(actor.couple(), actor.user(), filter.from(), filter.before()).stream()
                .filter(row -> filter.q().isEmpty() || contains(row.title(), filter.q())
                        || (row.destination() != null && contains(row.destination().name(), filter.q())))
                .sorted(Comparator.comparing(RecordService::position, ORDER)).toList();
    }

    private Map<String, String> thumbnails(List<Row> rows, String user) {
        return media.cardThumbnails(rows.stream().map(Row::cardId).toList(), user);
    }

    private static Summary summary(Row row, Map<String, String> thumbnails) {
        return new Summary(row.cardId(), row.driveId(), row.title(), row.date(), row.destination(),
                thumbnails.get(row.cardId()), row.distanceMeters(), row.activeDurationSeconds(), row.completedAt());
    }

    private static boolean contains(String source, String q) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(q);
    }

    private static String groupKey(Place destination) {
        if (destination.placeId() != null && !destination.placeId().isBlank()) {
            return "place:" + destination.placeId();
        }
        Coordinate point = destination.coordinate();
        return "coord:" + normalized(point.latitude()) + "," + normalized(point.longitude());
    }

    private static String normalized(double value) {
        return BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    private static <T> Page<T> page(List<T> sorted, Function<T, Row> row, Position cursor, int limit, String scope) {
        if (limit < 1 || limit > 100) {
            throw badRequest("limit은 1부터 100까지 지정해 주세요.");
        }
        List<T> selected = sorted.stream()
                .filter(item -> cursor == null || ORDER.compare(position(row.apply(item)), cursor) > 0)
                .limit(limit + 1L).toList();
        if (selected.size() <= limit) {
            return new Page<>(selected, null, false);
        }
        Position last = position(row.apply(selected.get(limit - 1)));
        String value = scope + "\n" + last.date() + "\n" + last.id();
        String next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return new Page<>(selected.subList(0, limit), next, true);
    }

    private static Position position(Row row) {
        return new Position(row.date(), row.cardId());
    }

    private static Position decode(String cursor, String expectedScope) {
        if (cursor == null) {
            return null;
        }
        if (cursor.length() > 256) {
            throw badRequest("유효한 cursor를 보내 주세요.");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\n", -1);
            if (parts.length == 3 && expectedScope.equals(parts[0]) && !parts[2].isBlank() && parts[2].length() <= 80) {
                return new Position(LocalDate.parse(parts[1]), parts[2]);
            }
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw badRequest("유효한 cursor를 보내 주세요.");
        }
        throw badRequest("유효한 cursor를 보내 주세요.");
    }

    private static String fingerprint(String endpoint, String couple, String user, Filter filter, Bounds bounds) {
        String value = endpoint + "\n" + couple + "\n" + user + "\n" + filter.from() + "\n" + filter.before()
                + "\n" + bounds + "\n" + filter.q();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static URI parseShareBase(String value) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("CARD_SHARE_BASE_URL requires an HTTPS application base URL");
        }
        return uri;
    }

    private static CardError invalid(String message) {
        return new CardError(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    private static CardError badRequest(String message) {
        return new CardError(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    record Query(String q, String date, String fromDate, String toDate, String cursor, int limit) {
    }

    private record Filter(String q, LocalDate from, LocalDate before) {
        static Filter parse(Query query) {
            if (query.date() != null && (query.fromDate() != null || query.toDate() != null)) {
                throw invalid("date와 날짜 범위를 함께 지정할 수 없습니다.");
            }
            if (query.q() != null && query.q().length() > 100) {
                throw invalid("검색어는 최대 100자까지 입력할 수 있습니다.");
            }
            LocalDate from = date(query.date() == null ? query.fromDate() : query.date());
            LocalDate to = date(query.date() == null ? query.toDate() : query.date());
            if (from != null && to != null && from.isAfter(to)) {
                throw invalid("시작 날짜와 종료 날짜를 확인해 주세요.");
            }
            String q = query.q() == null ? "" : query.q().strip().toLowerCase(Locale.ROOT);
            return new Filter(q, from, to == null ? null : to.plusDays(1));
        }

        private static LocalDate date(String value) {
            if (value == null) {
                return null;
            }
            try {
                if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    LocalDate date = LocalDate.parse(value);
                    if (date.getYear() > 0) {
                        return date;
                    }
                }
            } catch (DateTimeParseException exception) {
                throw invalid("조회할 날짜를 확인해 주세요.");
            }
            throw invalid("조회할 날짜를 확인해 주세요.");
        }
    }

    private record Bounds(double west, double south, double east, double north) {
        static Bounds parse(String value) {
            if (value == null) {
                return new Bounds(-180, -90, 180, 90);
            }
            String[] parts = value.split(",", -1);
            if (parts.length != 4) {
                throw invalid("지도 범위를 확인해 주세요.");
            }
            try {
                Bounds bounds = new Bounds(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
                if (Double.isFinite(bounds.west) && Double.isFinite(bounds.south)
                        && Double.isFinite(bounds.east) && Double.isFinite(bounds.north)
                        && bounds.west >= -180 && bounds.east <= 180 && bounds.west <= bounds.east
                        && bounds.south >= -90 && bounds.north <= 90 && bounds.south <= bounds.north) {
                    return bounds;
                }
            } catch (NumberFormatException exception) {
                throw invalid("지도 범위를 확인해 주세요.");
            }
            throw invalid("지도 범위를 확인해 주세요.");
        }

        boolean contains(Coordinate point) {
            return point.longitude() >= west && point.longitude() <= east
                    && point.latitude() >= south && point.latitude() <= north;
        }
    }

    private static final class Group {
        final String key;
        final Row latest;
        int count;

        Group(String key, Row latest) {
            this.key = key;
            this.latest = latest;
        }
    }

    private record Position(LocalDate date, String id) {
    }

    private record Page<T>(List<T> items, String nextCursor, boolean hasMore) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Summary(String cardId, String driveId, String title, LocalDate driveDate, Place destination,
            String thumbnailUrl, double distanceMeters, int activeDurationSeconds, Instant completedAt) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Records(long totalCount, long filteredCount, List<Summary> items, String nextCursor, boolean hasMore) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record RecordMap(long totalCount, List<Marker> markers, String nextCursor, boolean hasMore) {
    }

    record Marker(String groupKey, Coordinate coordinate, Summary latestCard, long recordCount) {
    }

    record Calendar(String month, List<Day> days) {
    }

    record Day(LocalDate date, long count) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record ShareLink(String url, String access, Instant expiresAt) {
    }
}
