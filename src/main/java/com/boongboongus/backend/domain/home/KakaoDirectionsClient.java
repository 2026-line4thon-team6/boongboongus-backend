package com.boongboongus.backend.domain.home;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class KakaoDirectionsClient {
    private static final Set<Integer> NO_ROUTE = Set.of(1, 101, 102, 103, 104, 105, 106, 107);
    private final RestClient http;
    private final URI baseUrl;
    private final String apiKey;

    KakaoDirectionsClient(@Value("${app.maps.kakao.mobility-base-url}") URI baseUrl,
            @Value("${app.maps.kakao.mobility-key:}") String apiKey) {
        this.http = RestClient.builder().requestFactory(KakaoPlaceClient.requestFactory()).build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    Directions find(PlaceRouteService.Input input) {
        if (apiKey.isBlank()) {
            throw unavailable();
        }
        var uri = UriComponentsBuilder.fromUri(baseUrl).path("/v1/directions")
                .queryParam("origin", parameter(input.origin().coordinate()))
                .queryParam("destination", parameter(input.destination().coordinate()))
                .queryParam("priority", input.preference().providerValue).queryParam("summary", false);
        String waypoints = input.waypoints().stream().map(place -> parameter(place.coordinate()))
                .collect(Collectors.joining("|"));
        if (!waypoints.isEmpty()) {
            uri.queryParam("waypoints", waypoints);
        }
        RoutePage page;
        try {
            page = http.get().uri(uri.build().encode().toUri())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey).retrieve().body(RoutePage.class);
        } catch (RestClientException exception) {
            throw unavailable();
        }
        if (page == null || page.routes() == null || page.routes().isEmpty()) {
            throw unavailable();
        }
        ProviderRoute route = page.routes().getFirst();
        if (route.resultCode() == null) {
            throw unavailable();
        }
        if (NO_ROUTE.contains(route.resultCode())) {
            throw new HomeExploreException(HttpStatus.UNPROCESSABLE_CONTENT, "ROUTE_NOT_FOUND",
                    "해당 지점들을 잇는 자동차 경로를 찾을 수 없습니다.");
        }
        Summary summary = route.summary();
        if (route.resultCode() != 0 || summary == null || summary.distance() == null || summary.duration() == null
                || summary.distance() < 0 || summary.duration() < 0 || route.sections() == null
                || route.sections().size() != input.waypoints().size() + 1) {
            throw unavailable();
        }
        return new Directions(encodePolyline(route.sections()), summary.distance(), summary.duration());
    }

    private static String encodePolyline(List<Section> sections) {
        var polyline = new StringBuilder();
        long previousLat = 0;
        long previousLng = 0;
        int points = 0;
        for (Section section : sections) {
            if (section == null || section.roads() == null || section.roads().isEmpty()) {
                throw unavailable();
            }
            for (Road road : section.roads()) {
                if (road == null || road.vertexes() == null || road.vertexes().size() < 2
                        || road.vertexes().size() % 2 != 0) {
                    throw unavailable();
                }
                for (int i = 0; i < road.vertexes().size(); i += 2) {
                    Double lng = road.vertexes().get(i);
                    Double lat = road.vertexes().get(i + 1);
                    if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                            || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
                        throw unavailable();
                    }
                    long nextLat = Math.round(lat * 100_000);
                    long nextLng = Math.round(lng * 100_000);
                    if (points == 0 || previousLat != nextLat || previousLng != nextLng) {
                        appendDelta(polyline, nextLat - previousLat);
                        appendDelta(polyline, nextLng - previousLng);
                        points++;
                    }
                    previousLat = nextLat;
                    previousLng = nextLng;
                }
            }
        }
        if (points < 2) {
            throw unavailable();
        }
        return polyline.toString();
    }

    private static void appendDelta(StringBuilder output, long delta) {
        long value = delta < 0 ? ~(delta << 1) : delta << 1;
        while (value >= 0x20) {
            output.append((char) ((value & 0x1f | 0x20) + 63));
            value >>= 5;
        }
        output.append((char) (value + 63));
    }

    private static String parameter(Coordinate coordinate) {
        return coordinate.longitude() + "," + coordinate.latitude();
    }

    private static HomeExploreException unavailable() {
        return new HomeExploreException(HttpStatus.SERVICE_UNAVAILABLE, "MAP_PROVIDER_UNAVAILABLE",
                "카카오모빌리티 경로를 사용할 수 없습니다.");
    }

    record Directions(String polyline, int distanceMeters, int durationSeconds) {
    }

    record RoutePage(List<ProviderRoute> routes) {
    }

    record ProviderRoute(@JsonProperty("result_code") Integer resultCode, Summary summary, List<Section> sections) {
    }

    record Summary(Integer distance, Integer duration) {
    }

    record Section(List<Road> roads) {
    }

    record Road(List<Double> vertexes) {
    }
}
