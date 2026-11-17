package com.boongboongus.backend.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.boongboongus.backend.domain.auth.config.KakaoLoginProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoLoginClientTest {
    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";
    private static final long KAKAO_ID = 5_000_000_001L;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void unlinksByKakaoMemberNumberWithAdminKey() {
        server.expect(requestTo(UNLINK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "KakaoAK test-admin-key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("target_id_type=user_id&target_id=5000000001"))
                .andRespond(withSuccess("{\"id\":5000000001}", MediaType.APPLICATION_JSON));

        client("test-admin-key").unlink(KAKAO_ID);

        server.verify();
    }

    @Test
    void rejectsUnexpectedMemberNumberInSuccessResponse() {
        server.expect(requestTo(UNLINK_URL))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));

        assertUnlinkFailed(client("test-admin-key"));
        server.verify();
    }

    @Test
    void mapsProviderErrorToUnlinkFailure() {
        server.expect(requestTo(UNLINK_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertUnlinkFailed(client("test-admin-key"));
        server.verify();
    }

    @Test
    void acceptsKakaosAlreadyUnlinkedError() {
        server.expect(requestTo(UNLINK_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":-101,\"msg\":\"not linked\"}"));

        client("test-admin-key").unlink(KAKAO_ID);
        server.verify();
    }

    @Test
    void rejectsUnrecognizedBadRequest() {
        server.expect(requestTo(UNLINK_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":-103}"));

        assertUnlinkFailed(client("test-admin-key"));
        server.verify();
    }

    @Test
    void failsWithoutCallingKakaoWhenAdminKeyIsMissing() {
        assertUnlinkFailed(client(""));
        server.verify();
    }

    private KakaoLoginClient client(String adminKey) {
        return new KakaoLoginClient(new KakaoLoginProperties("", List.of(), "", adminKey), builder.build());
    }

    private void assertUnlinkFailed(KakaoLoginClient client) {
        assertThatThrownBy(() -> client.unlink(KAKAO_ID))
                .isInstanceOfSatisfying(TokenExchangeException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(exception.code()).isEqualTo("PROVIDER_UNLINK_FAILED");
                });
    }
}
