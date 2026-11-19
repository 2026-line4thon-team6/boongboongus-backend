package com.boongboongus.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.domain.notification.service.NotificationService;
import com.boongboongus.backend.domain.push.WebPushGateway;
import com.boongboongus.backend.domain.push.WebPushSubscription;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "app.auth.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(NotificationPushIntegrationTest.Fixtures.class)
class NotificationPushIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired NotificationService notifications;
    @Autowired FakeGateway gateway;

    @BeforeEach
    void reset() {
        gateway.sent.clear();
        gateway.result.set(WebPushGateway.Result.SENT);
    }

    @Test
    void rejectsWebRegistrationThatIsNotAPushSubscription() throws Exception {
        String user = createUser("민준");

        mvc.perform(register(user, "dev_" + UUID.randomUUID(), "plain-token")).andExpect(status().isBadRequest());
        mvc.perform(register(user, "dev_" + UUID.randomUUID(),
                subscription("http://push.example.test/insecure"))).andExpect(status().isBadRequest());
    }

    @Test
    void pushesNewNotificationToRecipientsWebDevicesWithSenderNickname() throws Exception {
        String sender = createUser("민지");
        String recipient = createUser("민준");
        String device = "dev_" + UUID.randomUUID();
        mvc.perform(register(recipient, device, subscription("https://push.example.test/" + device)))
                .andExpect(status().isOk());
        String couple = "cpl_" + UUID.randomUUID();

        notifications.coupleChanged(sender, recipient, couple, 1, true);
        // 같은 이벤트가 다시 와도 휴대폰 알림은 한 번만
        notifications.coupleChanged(sender, recipient, couple, 1, true);

        List<Sent> sent = awaitSent(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().endpoint()).endsWith(device);
        JsonNode payload = json.readTree(sent.getFirst().payload());
        assertThat(payload.path("title").asString()).isEqualTo("민지님과 커플이 연결됐어요");
        assertThat(payload.path("type").asString()).isEqualTo("COUPLE_CONNECTED");
        assertThat(payload.path("targetType").asString()).isEqualTo("COUPLE");
        assertThat(payload.path("targetId").asString()).isEqualTo(couple);
        assertThat(payload.path("notificationId").asString()).startsWith("ntf_");
    }

    @Test
    void skipsSenderAndDisabledDevicesAndDropsExpiredSubscriptions() throws Exception {
        String sender = createUser("민지");
        String recipient = createUser("민준");
        String senderDevice = "dev_" + UUID.randomUUID();
        String offDevice = "dev_" + UUID.randomUUID();
        String goneDevice = "dev_" + UUID.randomUUID();
        mvc.perform(register(sender, senderDevice, subscription("https://push.example.test/" + senderDevice)))
                .andExpect(status().isOk());
        mvc.perform(register(recipient, offDevice, subscription("https://push.example.test/" + offDevice), false))
                .andExpect(status().isOk());
        mvc.perform(register(recipient, goneDevice, subscription("https://push.example.test/" + goneDevice)))
                .andExpect(status().isOk());
        gateway.result.set(WebPushGateway.Result.GONE);

        notifications.cardActivity(sender, recipient, "crd_" + UUID.randomUUID(), true, Instant.now());

        List<Sent> sent = awaitSent(1);
        assertThat(sent).extracting(Sent::endpoint).containsExactly("https://push.example.test/" + goneDevice);
        assertThat(json.readTree(sent.getFirst().payload()).path("title").asString())
                .isEqualTo("민지님이 드라이브 카드를 완성했어요");
        awaitDeleted(goneDevice);
        assertThat(count(offDevice)).isEqualTo(1);
        assertThat(count(senderDevice)).isEqualTo(1);
    }

    private List<Sent> awaitSent(int expected) throws InterruptedException {
        for (int i = 0; i < 50 && gateway.sent.size() < expected; i++) {
            Thread.sleep(50);
        }
        // 늦게 오는 중복 발송이 없는지 잠깐 더 기다린다
        Thread.sleep(200);
        return List.copyOf(gateway.sent);
    }

    private void awaitDeleted(String device) throws InterruptedException {
        for (int i = 0; i < 50 && count(device) > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(count(device)).isZero();
    }

    private int count(String device) {
        return jdbc.sql("SELECT COUNT(*) FROM device_push_tokens WHERE device_id = :id").param("id", device)
                .query(Integer.class).single();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String user,
            String device, String token) {
        return register(user, device, token, true);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register(String user,
            String device, String token, boolean enabled) {
        return put("/api/v1/devices/" + device + "/push-token")
                .header("Authorization", "Bearer " + accessToken(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("token", token, "platform", "WEB", "enabled", enabled)));
    }

    // 발급 메서드가 auth 패키지 전용이라 다른 통합 테스트처럼 리플렉션으로 부른다
    private String accessToken(String user) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                context.getBean("serviceTokens"), "access", user, "push-session", Instant.now());
    }

    private String subscription(String endpoint) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        ECPublicKey key = (ECPublicKey) generator.generateKeyPair().getPublic();
        byte[] point = new byte[65];
        point[0] = 4;
        copy(key.getW().getAffineX().toByteArray(), point, 1);
        copy(key.getW().getAffineY().toByteArray(), point, 33);
        var b64 = Base64.getUrlEncoder().withoutPadding();
        return json.writeValueAsString(java.util.Map.of("endpoint", endpoint,
                "keys", java.util.Map.of("p256dh", b64.encodeToString(point), "auth", b64.encodeToString(new byte[16]))));
    }

    private static void copy(byte[] value, byte[] target, int offset) {
        int length = Math.min(value.length, 32);
        System.arraycopy(value, value.length - length, target, offset + 32 - length, length);
    }

    private String createUser(String nickname) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO auth_users (id, kakao_id, nickname, onboarding_completed) VALUES (:id, :kakao, :name, TRUE)
                """).param("id", id).param("kakao", -ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE))
                .param("name", nickname).update();
        return id;
    }

    record Sent(String endpoint, byte[] payload) {
    }

    static class FakeGateway implements WebPushGateway {
        final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Result> result = new AtomicReference<>(Result.SENT);

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Result send(WebPushSubscription subscription, byte[] payload) {
            sent.add(new Sent(subscription.endpoint().toString(), payload));
            return result.get();
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        @Primary
        FakeGateway fakeGateway() {
            return new FakeGateway();
        }
    }
}
