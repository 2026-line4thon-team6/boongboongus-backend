package com.boongboongus.backend.domain.drive;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RestAreaStampIntegrationTest.Fixtures.class)
class RestAreaStampIntegrationTest {
    private static final String API = "/api/v1";
    private static final String NORTH = "fixture_rest_north";
    private static final String SOUTH = "fixture_rest_south";
    private static final String ROUTE_ID = "fixture_rest_route";
    private static final String ROAD = "테스트고속도로";
    private static final Coordinate ORIGIN = new Coordinate(37, 127);
    private static final Coordinate DESTINATION = new Coordinate(37.05, 127);
    private static final String POLYLINE = "_sh`F_sfhWozH?";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void catalog() {
        seed(NORTH, 127.0004, 0, "북쪽");
        seed(SOUTH, 126.9996, 180, "남쪽");
    }

    @Test
    void sameResponseSuppliesRouteAndDirectionMatchedMarkersAndGpsTriggersOneAlert() throws Exception {
        String couple = uniqueCouple();
        String drive = start(couple);
        mvc.perform(get(API + "/drives/" + drive + "/rest-areas").principal(() -> recorder(couple)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.routeId").value(ROUTE_ID))
                .andExpect(jsonPath("$.data.polyline").value(POLYLINE))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(NORTH))
                .andExpect(jsonPath("$.data.items[0].distanceAlongRouteMeters").isNumber());
        mvc.perform(get(API + "/drives/" + drive + "/rest-areas").principal(() -> "outside_driver"))
                .andExpect(status().isNotFound());
        mvc.perform(get(API + "/rest-areas").principal(() -> "single").param("q", "테스트 휴게소")
                .param("latitude", "37.02").param("longitude", "127.001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(NORTH))
                .andExpect(jsonPath("$.data.items[0].distanceMeters").isNumber());
        mvc.perform(get(API + "/rest-areas/" + NORTH).principal(() -> "single"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restArea.earned").value(false))
                .andExpect(jsonPath("$.data.detailsAvailable").value(false))
                .andExpect(jsonPath("$.data.menus.length()").value(0));
        Instant first = Instant.now().minusSeconds(70);
        mvc.perform(gps(couple, drive, 1, 37.0005, 127, 12, first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restAreaAlerts.length()").value(0));
        mvc.perform(gps(couple, drive, 2, 37.006, 127, 12, first.plusSeconds(20)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restAreaAlerts.length()").value(1))
                .andExpect(jsonPath("$.data.restAreaAlerts[0].restAreaId").value(NORTH))
                .andExpect(jsonPath("$.data.restAreaAlerts[0].distanceMeters").value(1557));
        mvc.perform(gps(couple, drive, 3, 37.007, 127, 12, first.plusSeconds(25)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.restAreaAlerts.length()").value(0));
    }

    private void seed(String id, double longitude, int bearing, String direction) {
        jdbc.sql("""
                INSERT INTO rest_areas (id, source_id, name, highway_name, direction, latitude, longitude, travel_bearing)
                VALUES (:id, :id, :name, :road, :direction, 37.02, :longitude, :bearing) ON CONFLICT DO NOTHING
                """).param("id", id).param("name", "테스트 휴게소 " + direction).param("road", ROAD)
                .param("direction", direction).param("longitude", longitude).param("bearing", bearing).update();
    }

    private String start(String couple) throws Exception {
        String prepare = """
                {"driverUserId":"%s","passengerUserId":"%s","recorderUserId":"%s","routeId":"%s"}
                """.formatted(recorder(couple), partner(couple), recorder(couple), ROUTE_ID);
        String drive = data(mvc.perform(write(API + "/drives", couple, recorder(couple),
                UUID.randomUUID().toString(), prepare)).andExpect(status().isCreated()).andReturn()).path("id").asText();
        mvc.perform(write(API + "/drives/" + drive + "/start", couple, recorder(couple),
                UUID.randomUUID().toString(), """
                {"expectedVersion":1,"initialCoordinate":{"latitude":37,"longitude":127}}
                """)).andExpect(status().isOk());
        jdbc.sql("UPDATE drives SET started_at = :at, state_changed_at = :at WHERE id = :drive")
                .param("at", Instant.now().minusSeconds(120).atOffset(ZoneOffset.UTC)).param("drive", drive).update();
        return drive;
    }

    private JsonNode data(MvcResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static MockHttpServletRequestBuilder gps(String couple, String drive, int sequence,
            double lat, double lng, int accuracy, Instant at) {
        return write(API + "/drives/" + drive + "/gps-batches", couple, recorder(couple),
                UUID.randomUUID().toString(), """
                {"samples":[{"sequence":%d,"recordedAt":"%s","coordinate":{"latitude":%s,"longitude":%s},
                "accuracyMeters":%d,"speedMetersPerSecond":null}]}
                """.formatted(sequence, at, lat, lng, accuracy));
    }

    private static MockHttpServletRequestBuilder write(String path, String couple, String user, String key, String body) {
        return post(path).principal(() -> user).header("X-Couple-Id", couple).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String uniqueCouple() {
        return "rest_test_" + UUID.randomUUID();
    }

    private static String recorder(String couple) {
        return couple + "_driver";
    }

    private static String partner(String couple) {
        return couple + "_partner";
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        CurrentCoupleLookup currentCoupleLookup() {
            return user -> {
                int suffix = user.lastIndexOf('_');
                if (suffix < 0) {
                    return Optional.empty();
                }
                String id = user.substring(0, suffix);
                return Optional.of(new CurrentCoupleLookup.Couple(id, recorder(id), partner(id)));
            };
        }

        @Bean
        DriveLocationConsentLookup consent() {
            return user -> true;
        }

        @Bean
        @Primary
        DriveRouteLookup restAreaRouteLookup() {
            return new DriveRouteLookup() {
                @Override
                public Optional<Route> findAccessible(String userId, String coupleId, String routeId) {
                    return Optional.of(new Route(new Place(null, "출발", "", ORIGIN),
                            new Place(null, "도착", "", DESTINATION)));
                }

                @Override
                public Optional<LiveRoute> findLiveForDrive(String userId, String coupleId, String routeId) {
                    return Optional.of(new LiveRoute(routeId, POLYLINE, List.of(new Road(ROAD,
                            List.of(ORIGIN, DESTINATION)))));
                }
            };
        }
    }
}