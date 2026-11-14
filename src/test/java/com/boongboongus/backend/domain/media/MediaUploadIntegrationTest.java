package com.boongboongus.backend.domain.media;

import static com.boongboongus.backend.domain.media.PhotoStorage.MAX_BYTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
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
class MediaUploadIntegrationTest {
    private static final String API = "/api/v1/media";
    private static final String OWNER = "media_owner";
    private static final String PARTNER = "media_partner";
    private static final String COUPLE = "media_couple";
    private static final String PNG = "image/png";
    private static final String WEBP = "image/webp";
    private static final String PRIVATE_TEXT = "test-only private image coordinates";

    @TempDir static Path storage;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired MediaService media;
    @Autowired MediaLinks links;
    @Autowired ApplicationContext context;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.media.storage-root", () -> storage.toString());
        registry.add("app.media.public-base-url", () -> "http://localhost:8080");
        registry.add("app.media.signing-key", () -> "test-only-not-a-deployment-secret");
    }

    @BeforeEach
    void clearReservations() {
        jdbc.sql("DELETE FROM card_photo_uploads WHERE couple_id = :couple").param("couple", COUPLE).update();
        addMember(OWNER, 900_011);
        addMember(PARTNER, 900_012);
        jdbc.sql("""
                INSERT INTO couples (id, first_member_id, second_member_id, start_date, mascot_name)
                VALUES (:couple, :owner, :partner, :start, 'test') ON CONFLICT DO NOTHING
                """).param("couple", COUPLE).param("owner", OWNER).param("partner", PARTNER)
                .param("start", LocalDate.of(2026, 1, 1)).update();
        jdbc.sql("UPDATE couples SET disconnected_at = NULL WHERE id = :id").param("id", COUPLE).update();
    }

    @Test
    void uploadCompletionRetryPrivateDownloadAndDeletion() throws Exception {
        byte[] original = pngWithMetadata();
        String createKey = key();
        Ticket ticket = issue(PNG, original.length, createKey);
        assertThat(issue(PNG, original.length, createKey)).isEqualTo(ticket);
        mvc.perform(create(OWNER, COUPLE, PNG, original.length + 1, createKey))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(complete(ticket, OWNER, hash(original), key())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("UPLOAD_NOT_FINISHED"));
        mvc.perform(put(ticket.url()).contentType(PNG).content(original)).andExpect(status().isNoContent());
        mvc.perform(complete(ticket, PARTNER, hash(original), key())).andExpect(status().isNotFound());
        mvc.perform(complete(ticket, OWNER, "0".repeat(64), key())).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("MEDIA_VALIDATION_FAILED"));

        String completeKey = key();
        JsonNode result = data(mvc.perform(complete(ticket, OWNER, hash(original), completeKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.width").value(3)).andReturn());
        assertThat(data(mvc.perform(complete(ticket, OWNER, hash(original), completeKey))
                .andExpect(status().isOk()).andReturn())).isEqualTo(result);
        assertThat(Files.exists(storage.resolve(ticket.id() + ".upload"))).isFalse();
        String storedPath = jdbc.sql("SELECT storage_path FROM card_photo_uploads WHERE id = :id")
                .param("id", ticket.id()).query(String.class).single();
        assertThat(Path.of(storedPath)).isRelative();
        assertThat(storage.resolve(storedPath)).isRegularFile();
        assertThat(result.has("storagePath")).isFalse();

        mvc.perform(get(API + "/" + ticket.id() + "/download-url").header(HttpHeaders.AUTHORIZATION, authorization(PARTNER)))
                .andExpect(status().isNotFound());
        URI download = downloadUrl(ticket, OWNER);
        var response = mvc.perform(get(download)).andExpect(status().isOk())
                .andExpect(content().contentType(PNG)).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(new String(response.getContentAsByteArray(), StandardCharsets.ISO_8859_1))
                .doesNotContain(PRIVATE_TEXT);
        assertThat(response.getContentAsByteArray()).hasSize(result.get("bytes").intValue());
        mvc.perform(put(ticket.url()).contentType(PNG).content(original)).andExpect(status().isNotFound());
        mvc.perform(remove(ticket, PARTNER)).andExpect(status().isNotFound());
        mvc.perform(remove(ticket, OWNER)).andExpect(status().isNoContent());
        mvc.perform(remove(ticket, OWNER)).andExpect(status().isNoContent());
        mvc.perform(get(download)).andExpect(status().isNotFound());
        mvc.perform(complete(ticket, OWNER, hash(original), completeKey)).andExpect(status().isNotFound());
        assertThat(storage.resolve(storedPath)).doesNotExist();
    }

    @Test
    void validatesRealWebpAndRevokesAccessAfterCoupleDisconnects() throws Exception {
        // Three by two, generated for this test with Pillow's lossless WebP encoder.
        byte[] image = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAkAAAAdQt1q0uf+BiOh/AAA=");
        Ticket ticket = issue(WEBP, image.length, key());
        mvc.perform(put(ticket.url()).contentType(WEBP).content(image)).andExpect(status().isNoContent());
        mvc.perform(complete(ticket, OWNER, hash(image), key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value(PNG))
                .andExpect(jsonPath("$.data.height").value(2));
        URI download = downloadUrl(ticket, OWNER);
        mvc.perform(get(download)).andExpect(status().isOk()).andExpect(content().contentType(PNG));
        jdbc.sql("UPDATE couples SET disconnected_at = :at WHERE id = :id")
                .param("at", OffsetDateTime.now()).param("id", COUPLE).update();
        mvc.perform(get(download)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + ticket.id() + "/download-url").header(HttpHeaders.AUTHORIZATION, authorization(OWNER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnsupportedOversizedCorruptAndTamperedRequests() throws Exception {
        mvc.perform(create(OWNER, COUPLE, PNG, MAX_BYTES + 1, key())).andExpect(status().is(413));
        mvc.perform(create(OWNER, COUPLE, "image/gif", 10, key())).andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(create(OWNER, "stale_couple", PNG, 10, key())).andExpect(status().isForbidden());
        mvc.perform(create(null, COUPLE, PNG, 10, key())).andExpect(status().isUnauthorized());

        byte[] image = pngWithMetadata();
        Ticket disguisedJpeg = issue("image/jpeg", image.length, key());
        mvc.perform(put(disguisedJpeg.url()).contentType("image/jpeg").content(image)).andExpect(status().isNoContent());
        mvc.perform(complete(disguisedJpeg, OWNER, hash(image), key())).andExpect(status().is(422));

        Ticket ticket = issue(PNG, image.length, key());
        mvc.perform(put(URI.create(ticket.url().toString() + "a")).contentType(PNG).content(image))
                .andExpect(status().isNotFound());
        mvc.perform(get(ticket.url())).andExpect(status().isNotFound());
        mvc.perform(put(ticket.url()).contentType(PNG).content(new byte[8])).andExpect(status().is(422));
        assertThat(Files.exists(storage.resolve(ticket.id() + ".upload"))).isFalse();
    }

    @Test
    void concurrentIssueWithSameKeyCreatesOneTicketAndExpiryRemovesFile() throws Exception {
        byte[] image = pngWithMetadata();
        String requestKey = key();
        Ticket ticket;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> issue(PNG, image.length, requestKey));
            var second = workers.submit(() -> issue(PNG, image.length, requestKey));
            ticket = first.get();
            assertThat(second.get()).isEqualTo(ticket);
        }
        mvc.perform(put(ticket.url()).contentType(PNG).content(image)).andExpect(status().isNoContent());
        jdbc.sql("UPDATE card_photo_uploads SET expires_at = :expiry WHERE id = :id")
                .param("expiry", OffsetDateTime.now().minusMinutes(1)).param("id", ticket.id()).update();
        mvc.perform(complete(ticket, OWNER, hash(image), key())).andExpect(status().isNotFound());
        media.cleanupExpired();
        assertThat(Files.exists(storage.resolve(ticket.id() + ".upload"))).isFalse();
        assertThat(jdbc.sql("SELECT status FROM card_photo_uploads WHERE id = :id").param("id", ticket.id())
                .query(String.class).single()).isEqualTo("DELETED");
    }

    @Test
    void appliesTemporaryTicketRateLimitWithoutCountingRetries() throws Exception {
        String requestKey = key();
        Ticket first = issue(PNG, 12, requestKey);
        for (int index = 1; index < 20; index++) {
            issue(PNG, 12, key());
        }
        assertThat(issue(PNG, 12, requestKey)).isEqualTo(first);
        mvc.perform(create(OWNER, COUPLE, PNG, 12, key())).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void jpegRemainsJpegAndExpiredSignedUrlsCannotBeUsed() throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB), "JPEG", buffer);
        byte[] jpeg = buffer.toByteArray();
        Ticket ticket = issue("image/jpeg", jpeg.length, key());

        var expiredUpload = URI.create(links.issue("PUT", ticket.id(), OWNER, Instant.now().minusSeconds(1)));
        mvc.perform(put(expiredUpload).contentType("image/jpeg").content(jpeg)).andExpect(status().isNotFound());
        mvc.perform(put(ticket.url()).contentType("image/jpeg").content(jpeg)).andExpect(status().isNoContent());
        mvc.perform(complete(ticket, OWNER, hash(jpeg), key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.data.width").value(4));
        mvc.perform(get(downloadUrl(ticket, OWNER))).andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void concurrentCompletionAndCancellationLeaveNoReadableFile() throws Exception {
        byte[] image = pngWithMetadata();
        Ticket ticket = issue(PNG, image.length, key());
        mvc.perform(put(ticket.url()).contentType(PNG).content(image)).andExpect(status().isNoContent());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var completion = workers.submit(() -> mvc.perform(complete(ticket, OWNER, hash(image), key()))
                    .andReturn().getResponse().getStatus());
            var deletion = workers.submit(() -> mvc.perform(remove(ticket, OWNER))
                    .andReturn().getResponse().getStatus());
            assertThat(completion.get()).isIn(200, 404);
            assertThat(deletion.get()).isEqualTo(204);
        }
        assertThat(Files.exists(storage.resolve(ticket.id() + ".upload"))).isFalse();
        assertThat(Files.exists(storage.resolve(ticket.id() + ".ready"))).isFalse();
        mvc.perform(get(API + "/" + ticket.id() + "/download-url").header(HttpHeaders.AUTHORIZATION, authorization(OWNER)))
                .andExpect(status().isNotFound());
    }

    private Ticket issue(String type, int bytes, String key) throws Exception {
        JsonNode ticket = data(mvc.perform(create(OWNER, COUPLE, type, bytes, key))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.method").value("PUT"))
                .andExpect(jsonPath("$.data.requiredHeaders['Content-Type']").value(type)).andReturn());
        return new Ticket(ticket.get("mediaId").asText(), URI.create(ticket.get("uploadUrl").asText()));
    }

    private MockHttpServletRequestBuilder create(String actor, String couple, String type, int bytes, String key) {
        var request = post(API + "/uploads").header("X-Couple-Id", couple).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fileName":"photo","contentType":"%s","bytes":%d,"purpose":"CARD_PHOTO"}
                        """.formatted(type, bytes));
        if (actor != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization(actor));
        }
        return request;
    }

    private MockHttpServletRequestBuilder complete(Ticket ticket, String actor, String hash, String key) {
        return post(API + "/" + ticket.id() + "/complete").header("X-Couple-Id", COUPLE).header("Idempotency-Key", key)
                .header(HttpHeaders.AUTHORIZATION, authorization(actor)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sha256":"%s"}
                        """.formatted(hash));
    }

    private MockHttpServletRequestBuilder remove(Ticket ticket, String actor) {
        return delete(API + "/" + ticket.id()).header("X-Couple-Id", COUPLE).header(HttpHeaders.AUTHORIZATION, authorization(actor));
    }

    private URI downloadUrl(Ticket ticket, String actor) throws Exception {
        return URI.create(data(mvc.perform(get(API + "/" + ticket.id() + "/download-url").header(HttpHeaders.AUTHORIZATION, authorization(actor)))
                .andExpect(status().isOk()).andReturn()).get("url").asText());
    }

    private void addMember(String id, long kakaoId) {
        jdbc.sql("""
                INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed)
                VALUES (:id, :kakao, :id, TRUE) ON CONFLICT DO NOTHING
                """).param("id", id).param("kakao", kakaoId).update();
    }

    private String authorization(String userId) {
        return "Bearer " + ReflectionTestUtils.invokeMethod(
                context.getBean("serviceTokens"), "access", userId, "media-session", Instant.now());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] pngWithMetadata() throws Exception {
        var raw = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "PNG", raw);
        byte[] png = raw.toByteArray();
        var withText = new ByteArrayOutputStream();
        withText.write(png, 0, 33);
        try (var chunk = new DataOutputStream(withText)) {
            byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
            byte[] value = ("Comment" + (char) 0 + PRIVATE_TEXT).getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(type);
            crc.update(value);
            chunk.writeInt(value.length);
            chunk.write(type);
            chunk.write(value);
            chunk.writeInt((int) crc.getValue());
            chunk.write(png, 33, png.length - 33);
        }
        return withText.toByteArray();
    }

    private record Ticket(String id, URI url) {
    }

}
