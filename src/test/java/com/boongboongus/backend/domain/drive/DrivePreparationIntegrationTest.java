package com.boongboongus.backend.domain.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        DriveRouteLookup driveRouteLookup() {
            return (userId, coupleId, routeId) -> {
                if (!"fixture_route".equals(routeId) || !userId.startsWith(coupleId + "_")) {
                    return Optional.empty();
                }
                return Optional.of(new Route(
                        new Place(null, "예시 출발지", "", new Coordinate(37.5, 127.0)),
                        new Place("place_fixture", "예시 도착지", "", new Coordinate(37.6, 127.1))));
            };
        }
    }
}
