package com.boongboongus.backend.domain.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "app.auth.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DriveCardIntegrationTest {
    private static final String API = "/api/v1";
    @TempDir static Path storage;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired ApplicationContext context;
    @Autowired CardService cards;
    String couple;
    String driver;
    String passenger;
    String drive;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage-root", () -> storage.toString());
        registry.add("app.media.public-base-url", () -> "http://localhost:8080");
        registry.add("app.media.signing-key", () -> "test-only-card-photos-signing-key-123");
    }

    @BeforeEach
    void seedDrive() {
        String suffix = UUID.randomUUID().toString();
        couple = "couple_" + suffix;
        driver = "driver_" + suffix;
        passenger = "passenger_" + suffix;
        drive = "drive_" + suffix;
        long kakaoId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        addMember(driver, "동민", kakaoId);
        addMember(passenger, "민지", kakaoId - 1);
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:id, :driver, :passenger, :start, '붕붕이')
                """).param("id", couple).param("driver", driver).param("passenger", passenger)
                .param("start", LocalDate.of(2026, 1, 1)).update();
        jdbc.sql("""
                INSERT INTO drives (id, couple_id, title, status, active_slot, driver_user_id, passenger_user_id,
                    recorder_user_id, control_version, started_at, state_changed_at)
                VALUES (:id, :couple, '주행', 'ACTIVE', 1, :driver, :passenger, :driver, 2, :start, :start)
                """).param("id", drive).param("couple", couple).param("driver", driver).param("passenger", passenger)
                .param("start", OffsetDateTime.now().minusMinutes(5)).update();
    }

    @Test
    void endsOnceKeepsDraftPrivateAndCompletesWithOneNotificationAndPushEvent() throws Exception {
        String card = endDrive();
        Instant firstSample = Instant.now().minusSeconds(250);
        var gps = jsonRequest(post(API + "/drives/" + drive + "/gps-batches"), driver, """
                {"samples":[
                    {"sequence":1,"recordedAt":"%s","coordinate":{"latitude":37.6,"longitude":127.1},
                     "accuracyMeters":8,"speedMetersPerSecond":null},
                    {"sequence":2,"recordedAt":"%s","coordinate":{"latitude":37.6001,"longitude":127.1},
                     "accuracyMeters":8,"speedMetersPerSecond":null}
                ]}
                """.formatted(firstSample, firstSample.plusSeconds(10)))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        mvc.perform(gps).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedSequences.length()").value(2));
        JsonNode refreshed = data(mvc.perform(auth(get(cardPath(card)), driver)).andExpect(status().isOk()).andReturn());
        assertThat(refreshed.path("distanceMeters").asDouble()).isBetween(11.0, 12.0);
        assertThat(refreshed.path("version").asInt()).isEqualTo(1);

        mvc.perform(auth(get(API + "/drives/" + drive + "/card"), passenger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.driverUserId").value(driver))
                .andExpect(jsonPath("$.data.status").value("UNWRITTEN"))
                .andExpect(jsonPath("$.data.reflections").isEmpty());
        mvc.perform(auth(get(API + "/home"), passenger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingCards[0].cardId").value(card));

        mvc.perform(reflect(card, driver, "비공개 초안", false, 0)).andExpect(status().isOk());
        mvc.perform(auth(get(cardPath(card)), passenger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reflections").isEmpty());
        mvc.perform(complete(card, passenger, UUID.randomUUID().toString(), 1))
                .andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("CARD_CONTENT_REQUIRED"));
        mvc.perform(reflect(card, driver, "완성한 소감", true, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("DRIVER")).andExpect(jsonPath("$.data.author.nickname").value("동민"));
        mvc.perform(reflect(card, driver, "완성한 소감", true, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(auth(get(cardPath(card)), passenger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reflections[0].text").value("완성한 소감"))
                .andExpect(jsonPath("$.data.version").value(1));

        String key = UUID.randomUUID().toString();
        mvc.perform(complete(card, passenger, key, 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.version").value(2));
        mvc.perform(complete(card, passenger, key, 1)).andExpect(status().isOk());
        assertThat(countNotifications(card, "REFLECTION_SUBMITTED", passenger)).isEqualTo(1);
        assertThat(countNotifications(card, "CARD_COMPLETED", driver)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT recipient_user_id FROM card_completion_push_events WHERE card_id = :card")
                .param("card", card).query(String.class).single()).isEqualTo(driver);
        mvc.perform(auth(get(API + "/home"), passenger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingCards").isEmpty());

        pastDeadline(card);
        mvc.perform(auth(get(cardPath(card)), passenger)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.canEdit").value(false));
        mvc.perform(reflect(card, passenger, "뒤늦은 소감", true, 0)).andExpect(status().isGone());
    }

    @Test
    void attachesPhotosInOrderAndProtectsTheLastContentOfCompletedCard() throws Exception {
        String card = endDrive();
        String first = readyPhoto();
        String second = readyPhoto();
        mvc.perform(draft(card, passenger, photos(1, first, second))).andExpect(status().is(422));
        mvc.perform(draft(card, driver, photos(1, first, second))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos[0].mediaId").value(first))
                .andExpect(jsonPath("$.data.photos[1].mediaId").value(second));
        mvc.perform(draft(card, passenger, photos(2, second, first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos[0].mediaId").value(second));
        mvc.perform(auth(delete(API + "/media/" + first), driver).header("X-Couple-Id", couple))
                .andExpect(status().isConflict());
        mvc.perform(complete(card, driver, UUID.randomUUID().toString(), 3)).andExpect(status().isOk());
        mvc.perform(draft(card, driver, photos(4))).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("CARD_CONTENT_REQUIRED"));
        mvc.perform(reflect(card, passenger, "소감", true, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("PASSENGER"));
        mvc.perform(draft(card, driver, photos(4))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.photos").isEmpty());
        assertThat(photoState(first)).isEqualTo("DELETED");
        assertThat(photoState(second)).isEqualTo("DELETED");
    }

    @Test
    void expiresUncompletedCardAndReleasesDraftData() throws Exception {
        String card = endDrive();
        String photo = readyPhoto();
        mvc.perform(draft(card, driver, photos(1, photo))).andExpect(status().isOk());
        mvc.perform(reflect(card, passenger, "정리할 소감", false, 0)).andExpect(status().isOk());
        pastDeadline(card);
        mvc.perform(auth(get(API + "/drives/" + drive + "/card"), driver)).andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("CARD_EXPIRED"));
        cards.expireCards();
        assertThat(photoState(photo)).isEqualTo("DELETED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM card_reflections WHERE card_id = :id")
                .param("id", card).query(Integer.class).single()).isZero();
    }

    private String endDrive() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = auth(post(API + "/drives/" + drive + "/end"), driver)
                .header("X-Couple-Id", couple).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"expectedVersion":2,"reason":"USER_ENDED","lastRecordedSequence":0}
                        """);
        String card = data(mvc.perform(request).andExpect(status().isOk()).andReturn()).path("cardId").asText();
        assertThat(card).startsWith("crd_");
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.data.cardId").value(card));
        return card;
    }

    private void addMember(String id, String nickname, long kakaoId) {
        jdbc.sql("""
                INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed) VALUES (:id, :kakao, :name, TRUE)
                """).param("id", id).param("kakao", kakaoId).param("name", nickname).update();
    }

    private String readyPhoto() throws Exception {
        String id = "med_" + UUID.randomUUID();
        jdbc.sql("INSERT INTO card_photo_upload_owners (user_id) VALUES (:user) ON CONFLICT DO NOTHING")
                .param("user", driver).update();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.sql("""
                INSERT INTO card_photo_uploads (id, user_id, couple_id, storage_path, status, declared_type,
                    declared_bytes, created_at, expires_at, sha256, content_type, content_bytes, width, height)
                VALUES (:id, :user, :couple, :path, 'READY', 'image/png', 1, :now, :expiry, :hash, 'image/png', 1, 1, 1)
                """).param("id", id).param("user", driver).param("couple", couple).param("path", id + ".ready")
                .param("now", now).param("expiry", now.plusHours(24)).param("hash", "0".repeat(64)).update();
        Files.write(storage.resolve(id + ".ready"), new byte[] {1});
        return id;
    }

    private void pastDeadline(String id) {
        jdbc.sql("UPDATE drive_cards SET editable_until = :time WHERE id = :id")
                .param("time", OffsetDateTime.now().minusSeconds(5)).param("id", id).update();
    }

    private String photoState(String id) {
        return jdbc.sql("SELECT status FROM card_photo_uploads WHERE id = :id").param("id", id).query(String.class).single();
    }

    private int countNotifications(String card, String type, String user) {
        return jdbc.sql("SELECT COUNT(*) FROM notifications WHERE target_id = :id AND type = :type AND recipient_user_id = :user")
                .param("id", card).param("type", type).param("user", user).query(Integer.class).single();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String user) {
        String token = ReflectionTestUtils.invokeMethod(
                context.getBean("serviceTokens"), "access", user, "card-session", Instant.now());
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder complete(String id, String user, String key, int version) {
        return jsonRequest(post(cardPath(id) + "/complete"), user, json.writeValueAsString(new CardController.Complete(version)))
                .header("Idempotency-Key", key);
    }

    private MockHttpServletRequestBuilder draft(String id, String user, String body) {
        return jsonRequest(patch(cardPath(id) + "/draft"), user, body);
    }

    private MockHttpServletRequestBuilder reflect(String id, String user, String text, boolean submitted, int version) {
        return jsonRequest(put(cardPath(id) + "/reflections/me"), user, json.writeValueAsString(
                new CardController.Reflection(text, submitted, version)));
    }

    private MockHttpServletRequestBuilder jsonRequest(MockHttpServletRequestBuilder request, String user, String body) {
        return auth(request, user).header("X-Couple-Id", couple).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String photos(int version, String... ids) {
        return json.writeValueAsString(java.util.Map.of("expectedVersion", version, "photoIds", ids));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String cardPath(String id) {
        return API + "/cards/" + id;
    }
}
