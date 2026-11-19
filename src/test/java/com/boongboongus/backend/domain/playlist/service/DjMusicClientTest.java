package com.boongboongus.backend.domain.playlist.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.boongboongus.backend.domain.playlist.dto.PlaylistRequest;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

class DjMusicClientTest {
    private static final String GROQ = "https://api.groq.com/openai/v1/chat/completions";
    private static final String SPOTIFY_TOKEN = "https://accounts.spotify.com/api/token";
    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DjMusicClient client = new DjMusicClient(json, "test-key", "test-model",
            "test-id", "test-secret", builder.build());
    private final PlaylistRequest request = new PlaylistRequest(null, List.of("ROMANTIC"), "SUNNY", List.of("K_POP"));

    @Test
    void retriesATimeoutOnce() {
        server.expect(requestTo(GROQ)).andRespond(withException(new SocketTimeoutException("test timeout")));
        expectCompletion("{\"tracks\":[]}");

        assertThat(client.recommend(request, 180)).isEmpty();
        server.verify();
    }

    @Test
    void retriesServerErrorsOnceAndStopsAfterSecondFailure() {
        server.expect(requestTo(GROQ)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(GROQ)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.recommend(request, 180))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN", "TOO_MANY_REQUESTS"})
    void doesNotImmediatelyRetryCredentialsOrRateLimitErrors(HttpStatus status) {
        server.expect(requestTo(GROQ)).andRespond(withStatus(status));

        assertThatThrownBy(() -> client.recommend(request, 180))
                .isInstanceOfSatisfying(RestClientResponseException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(status));
        server.verify();
    }

    @Test
    void retriesSpotifyTokenAndSearchWithoutRepeatingGroqRecommendation() {
        expectCompletion("{\"tracks\":[{\"title\":\"Song\",\"artist\":\"Artist\"}]}");
        server.expect(requestTo(SPOTIFY_TOKEN)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(SPOTIFY_TOKEN))
                .andRespond(withSuccess("{\"access_token\":\"test-token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        String search = "https://api.spotify.com/v1/search?type=track&market=KR&limit=5&q=track%3ASong%20artist%3AArtist";
        server.expect(requestTo(search)).andRespond(withException(new SocketTimeoutException("test timeout")));
        server.expect(requestTo(search))
                .andRespond(withSuccess("{\"tracks\":{\"items\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.recommend(request, 180)).isEmpty();
        server.verify();
    }

    private void expectCompletion(String content) {
        String body = json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        server.expect(requestTo(GROQ)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
