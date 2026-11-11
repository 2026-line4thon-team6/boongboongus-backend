package com.boongboongus.backend.domain.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.drive.CurrentCoupleLookup.Couple;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Coordinate;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Place;
import com.boongboongus.backend.domain.drive.DriveRouteLookup.Route;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DrivePreparationIntegrationTest.Fixtures.class)
class DrivePreparationIntegrationTest {
    private static final String BASE = "/api/v1/drives";
    private final String couple = "test_couple_" + UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper json;

    @Test
    void createsRestoresCancelsAndFreesTheActiveSlot() throws Exception {
        current(couple + "_b").andExpect(status().isOk()).andExpect(jsonPath("$.data.drive").value((Object) null));
        String id = driveId(mvc.perform(prepare(couple + "_a", key(), roles(couple)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PREPARING"))
                .andExpect(jsonPath("$.data.title").value("우리의 드라이브"))
                .andExpect(jsonPath("$.data.startedAt").value((Object) null))
                .andExpect(jsonPath("$.data.metrics.gpsQuality").value("MISSING"))
                .andExpect(jsonPath("$.data.controlVersion").value(1)));
        current(couple + "_b").andExpect(status().isOk()).andExpect(jsonPath("$.data.drive.id").value(id));
        mvc.perform(cancel(couple + "_b", id, key(), 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.controlVersion").value(2))
                .andExpect(jsonPath("$.data.endedAt").value((Object) null))
                .andExpect(jsonPath("$.data.canCreateCard").value(false))
                .andExpect(jsonPath("$.data.cardId").value((Object) null));
        current(couple + "_a").andExpect(jsonPath("$.data.drive").value((Object) null));
        String next = driveId(mvc.perform(prepare(couple + "_a", key(), roles(couple)))
                .andExpect(status().isCreated()));
        assertThat(next).isNotEqualTo(id);
    }

    @Test
    void replaysOriginalResponsesAndRejectsReusingAKeyForDifferentIntent() throws Exception {
        String prepareKey = key();
        String cancelKey = key();
        String body = roles(couple);
        String user = couple + "_a";
        String id = driveId(mvc.perform(prepare(user, prepareKey, body)).andExpect(status().isCreated()));
        mvc.perform(prepare(user, prepareKey, body.replace("{", "{\"title\":\"다른 제목\",")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(prepare(user, key(), body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACTIVE_DRIVE_EXISTS"));
        mvc.perform(cancel(user, id, cancelKey, 1)).andExpect(status().isOk());
        mvc.perform(cancel(user, id, cancelKey, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.controlVersion").value(2));
        mvc.perform(cancel(user, id, cancelKey, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(prepare(user, prepareKey, body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("PREPARING"));
        current(user).andExpect(jsonPath("$.data.drive").value((Object) null));
    }

    @Test
    void anExpiredKeyCanBeUsedForANewSession() throws Exception {
        String originalKey = key();
        String user = couple + "_a";
        String body = roles(couple);
        String id = driveId(mvc.perform(prepare(user, originalKey, body)).andExpect(status().isCreated()));
        mvc.perform(cancel(user, id, key(), 1)).andExpect(status().isOk());
        jdbc.sql("""
                UPDATE drive_idempotency SET expires_at = :expiry
                WHERE couple_id = :couple AND user_id = :userId AND idempotency_key = :key
                """).param("expiry", Instant.now().minusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("couple", couple).param("userId", user).param("key", originalKey).update();
        String next = driveId(mvc.perform(prepare(user, originalKey, body)).andExpect(status().isCreated()));
        assertThat(next).isNotEqualTo(id);
    }

    @Test
    void concurrentMembersCanCreateOnlyOneActiveSession() throws Exception {
        var results = concurrently(
                prepare(couple + "_a", key(), roles(couple)),
                prepare(couple + "_b", key(), roles(couple)));
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM drives WHERE couple_id = :couple AND active_slot = 1")
                .param("couple", couple).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void concurrentRetriesOfTheSameRequestReturnTheSameSession() throws Exception {
        String requestKey = key();
        var results = concurrently(
                prepare(couple + "_a", requestKey, roles(couple)),
                prepare(couple + "_a", requestKey, roles(couple)));
        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactly(201, 201);
        assertThat(readId(results.get(0))).isEqualTo(readId(results.get(1)));
    }

    @Test
    void validatesCallerCoupleAndRoles() throws Exception {
        mvc.perform(get(BASE + "/active").header("X-Couple-Id", couple).header("Authorization", "Bearer ignored"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        current("test_unconnected").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_REQUIRED"));
        mvc.perform(prepare(couple + "_a", "different_couple", key(), roles(couple)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COUPLE_CONTEXT_CHANGED"));
        mvc.perform(prepare(couple + "_a", key(), roles(couple).replace(couple + "_b", couple + "_a")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("INVALID_DRIVE_MEMBERS"));
        String id = driveId(mvc.perform(prepare(couple + "_a", key(), roles(couple))).andExpect(status().isCreated()));
        String other = "test_couple_" + UUID.randomUUID();
        mvc.perform(cancel(other + "_a", id, key(), 1)).andExpect(status().isNotFound());
        current(other + "_a").andExpect(jsonPath("$.data.drive").value((Object) null));
    }

    @Test
    void validatesVersionAndOnlyCancelsPreparingSessions() throws Exception {
        String user = couple + "_a";
        String id = driveId(mvc.perform(prepare(user, key(), roles(couple))).andExpect(status().isCreated()));
        mvc.perform(cancel(user, id, key(), 1).content("{\"expectedVersion\":1.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(cancel(user, id, key(), 1).content("{\"expectedVersion\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(cancel(user, id, key(), 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(cancel(user, id, key(), 1)).andExpect(status().isOk());
        mvc.perform(cancel(user, id, key(), 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DRIVE_STATE_CONFLICT"));
    }

    @Test
    void acceptsOnlyAccessibleRouteSnapshots() throws Exception {
        String user = couple + "_a";
        mvc.perform(prepare(user, key(), roles(couple).replace("{", "{\"routeId\":\"unknown\",")))
                .andExpect(status().isNotFound());
        mvc.perform(prepare(user, key(), roles(couple).replace("{", "{\"routeId\":\"fixture_route\",")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.routeId").value("fixture_route"))
                .andExpect(jsonPath("$.data.origin.coordinate.latitude").value(37.5))
                .andExpect(jsonPath("$.data.destination.name").value("예시 도착지"));
        current(user).andExpect(jsonPath("$.data.drive.destination.name").value("예시 도착지"));
    }

    @Test
    void rejectsInvalidInputAndUnexpectedFields() throws Exception {
        String user = couple + "_a";
        mvc.perform(prepare(user, "too-short", roles(couple))).andExpect(status().isBadRequest());
        mvc.perform(prepare(user, key(), "{}")).andExpect(status().is(422));
        mvc.perform(prepare(user, key(), roles(couple).replace("{", "{\"status\":\"ACTIVE\",")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void controlsDriveWithoutResettingTimesOrOriginalOriginWhenRerouting() throws Exception {
        String driver = couple + "_a";
        String recorder = couple + "_b";
        String id = driveId(mvc.perform(prepare(driver, key(), roles(couple))).andExpect(status().isCreated()));
        mvc.perform(update(recorder, id, """
                {"title":"함께 보는 노을","routeId":"fixture_route","expectedVersion":1}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.controlVersion").value(2));
        String startKey = key();
        MvcResult started = mvc.perform(control(recorder, id, "start", startKey, startBody(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.controlVersion").value(3))
                .andExpect(jsonPath("$.data.metrics.activeDurationSeconds").value(0))
                .andReturn();
        String startedAt = json.readTree(started.getResponse().getContentAsString())
                .path("data").path("startedAt").asText();
        // Leave a margin for timestamp rounding at the 120-second assertion boundary.
        jdbc.sql("UPDATE drives SET state_changed_at = :time WHERE id = :id")
                .param("time", Instant.now().minusSeconds(121).atOffset(ZoneOffset.UTC)).param("id", id).update();

        String pauseKey = key();
        mvc.perform(control(driver, id, "pause", pauseKey, version(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.pausedAt").isString())
                .andExpect(jsonPath("$.data.metrics.activeDurationSeconds").value(org.hamcrest.Matchers.greaterThanOrEqualTo(120)))
                .andExpect(jsonPath("$.data.controlVersion").value(4));
        mvc.perform(update(recorder, id, """
                {"routeId":"fixture_reroute","expectedVersion":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("함께 보는 노을"))
                .andExpect(jsonPath("$.data.routeId").value("fixture_reroute"))
                .andExpect(jsonPath("$.data.origin.name").value("새 경로 출발지"))
                .andExpect(jsonPath("$.data.startedAt").value(startedAt))
                .andExpect(jsonPath("$.data.controlVersion").value(5));
        mvc.perform(control(driver, id, "resume", key(), version(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.pausedAt").value((Object) null))
                .andExpect(jsonPath("$.data.controlVersion").value(6));
        mvc.perform(control(driver, id, "pause", pauseKey, version(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.controlVersion").value(4));
        mvc.perform(control(recorder, id, "start", startKey, startBody(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startedAt").value(startedAt))
                .andExpect(jsonPath("$.data.controlVersion").value(3));
        mvc.perform(get(BASE + "/{id}", id).principal(() -> recorder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.controlVersion").value(6));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM drive_pause_periods WHERE drive_id = :id AND resumed_at IS NOT NULL
                """).param("id", id).query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT initial_origin_snapshot FROM drives WHERE id = :id")
                .param("id", id).query(String.class).single()).contains("예시 출발지");
        assertThat(jdbc.sql("SELECT initial_coordinate FROM drives WHERE id = :id")
                .param("id", id).query(String.class).single()).contains("37.5");
    }

    @Test
    void enforcesAccessLocationAndState() throws Exception {
        String recorder = couple + "_b";
        String driver = couple + "_a";
        String other = "test_couple_" + UUID.randomUUID() + "_a";
        String id = driveId(mvc.perform(prepare(driver, key(), roles(couple))).andExpect(status().isCreated()));
        mvc.perform(get(BASE + "/{id}", id).principal(() -> other)).andExpect(status().isNotFound());
        mvc.perform(control(driver, id, "start", key(), startBody(1))).andExpect(status().isForbidden());
        mvc.perform(control(recorder, id, "start", key(), startBody(1)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("DESTINATION_REQUIRED"));
        mvc.perform(control(driver, id, "pause", key(), version(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DRIVE_STATE_CONFLICT"));
        mvc.perform(update(driver, id, version(1))).andExpect(status().is(422));
        mvc.perform(update(driver, id, """
                {"title":null,"routeId":"fixture_route","expectedVersion":1}
                """)).andExpect(status().isBadRequest());
        mvc.perform(update(driver, id, """
                {"routeId":"fixture_route","expectedVersion":1}
                """)).andExpect(status().isOk());
        mvc.perform(control(recorder, id, "start", key(), version(2)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("LOCATION_REQUIRED"));
        mvc.perform(control(recorder, id, "start", key(), startBody(2).replace("37.5", "90.1")))
                .andExpect(status().is(422));
        mvc.perform(control(recorder, id, "start", key(), startBody(2).replace("37.5", "\"37.5\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(control(recorder, id, "start", key(), startBody(2))).andExpect(status().isOk());
        mvc.perform(control(driver, id, "pause", key(), version(2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        mvc.perform(control(driver, id, "resume", key(), version(3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DRIVE_STATE_CONFLICT"));
    }

    @Test
    void rejectsStartWithoutRecordedLocationConsent() throws Exception {
        String noConsentCouple = "test_couple_no_consent_" + UUID.randomUUID();
        String recorder = noConsentCouple + "_b";
        String id = driveId(mvc.perform(prepare(recorder, noConsentCouple, key(),
                roles(noConsentCouple).replace("{", "{\"routeId\":\"fixture_route\",")))
                .andExpect(status().isCreated()));
        mvc.perform(control(recorder, id, "start", key(), startBody(1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("주행을 시작하려면 위치 정보 동의가 필요합니다."));
    }

    @Test
    void onlyOneConcurrentPauseCanUseTheSameVersion() throws Exception {
        String recorder = couple + "_b";
        String id = driveId(mvc.perform(prepare(recorder, key(),
                roles(couple).replace("{", "{\"routeId\":\"fixture_route\","))).andExpect(status().isCreated()));
        mvc.perform(control(recorder, id, "start", key(), startBody(1))).andExpect(status().isOk());
        var results = concurrently(control(recorder, id, "pause", key(), version(2)),
                control(couple + "_a", id, "pause", key(), version(2)));
        assertThat(results).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM drive_pause_periods WHERE drive_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1L);
    }

    private MockHttpServletRequestBuilder update(String user, String driveId, String body) {
        return patch(BASE + "/{id}", driveId).principal(() -> user).header("X-Couple-Id", couple)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder control(String user, String driveId, String action, String requestKey,
            String body) {
        String currentCouple = user.substring(0, user.length() - 2);
        return post(BASE + "/{id}/" + action, driveId).principal(() -> user)
                .header("X-Couple-Id", currentCouple).header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String version(int version) {
        return "{\"expectedVersion\":" + version + "}";
    }

    private static String startBody(int version) {
        return """
                {"expectedVersion":%d,"initialCoordinate":{"latitude":37.5,"longitude":127.0}}
                """.formatted(version);
    }

    private MockHttpServletRequestBuilder prepare(String user, String requestKey, String body) {
        return prepare(user, couple, requestKey, body);
    }

    private MockHttpServletRequestBuilder prepare(String user, String requestedCouple, String requestKey, String body) {
        return post(BASE).principal(() -> user).header("X-Couple-Id", requestedCouple)
                .header("Idempotency-Key", requestKey).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder cancel(String user, String driveId, String requestKey, int version) {
        String currentCouple = user.substring(0, user.length() - 2);
        return post(BASE + "/{id}/cancel", driveId).principal(() -> user)
                .header("X-Couple-Id", currentCouple).header("Idempotency-Key", requestKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":" + version + "}");
    }

    private ResultActions current(String user) throws Exception {
        return mvc.perform(get(BASE + "/active").principal(() -> user));
    }

    private String driveId(ResultActions result) throws Exception {
        return readId(result.andReturn());
    }

    private String readId(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private List<MvcResult> concurrently(
            MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(first).andReturn();
            });
            var two = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return mvc.perform(second).andReturn();
            });
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        }
    }

    private static String roles(String id) {
        return """
                {"driverUserId":"%s_a","passengerUserId":"%s_b","recorderUserId":"%s_b"}
                """.formatted(id, id, id);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        CurrentCoupleLookup currentCoupleLookup() {
            return userId -> {
                if (!userId.startsWith("test_couple_") || !(userId.endsWith("_a") || userId.endsWith("_b"))) {
                    return Optional.empty();
                }
                String id = userId.substring(0, userId.length() - 2);
                return Optional.of(new Couple(id, id + "_a", id + "_b"));
            };
        }

        @Bean
        DriveLocationConsentLookup driveLocationConsentLookup() {
            return userId -> !userId.startsWith("test_couple_no_consent_");
        }

        @Bean
        DriveRouteLookup driveRouteLookup() {
            return (userId, coupleId, routeId) -> {
                if (!List.of("fixture_route", "fixture_reroute").contains(routeId)
                        || !userId.startsWith(coupleId + "_")) {
                    return Optional.empty();
                }
                String originName = "fixture_route".equals(routeId) ? "예시 출발지" : "새 경로 출발지";
                return Optional.of(new Route(
                        new Place(null, originName, "", new Coordinate(37.5, 127.0)),
                        new Place("place_fixture", "예시 도착지", "", new Coordinate(37.6, 127.1))));
            };
        }
    }
}
