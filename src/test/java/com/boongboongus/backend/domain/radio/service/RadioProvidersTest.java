package com.boongboongus.backend.domain.radio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.boongboongus.backend.domain.radio.dto.RadioRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class RadioProvidersTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RadioProviders providers = new RadioProviders("test-groq", "test-model", "test-voice", builder.build());

    @Test
    void composesStoryVerbatimAndSendsTheSameScriptToTts() {
        String story = "오늘은 둘이 첫 여행을 떠나요.\n설레요! " + "가".repeat(270);
        String comment = "첫 여행이라 많이 설레시겠어요. 함께 즐거운 추억을 쌓으시길 바라요.";
        expectCompletion(comment);
        String expected = "안녕하세요. 붕붕이 DJ입니다. 오늘 사연자가 보내주신 사연이네요.\n"
                + story + " 라고 보내주셨네요.\n" + comment + "\n오늘도 즐거운 드라이브 시간 보내세요.";
        server.expect(requestTo("https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb?output_format=mp3_44100_128"))
                .andExpect(content().json(json.writeValueAsString(Map.of(
                        "text", expected, "model_id", "eleven_multilingual_v2"))))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.parseMediaType("audio/mpeg")));

        String script = providers.script(new RadioRequest(RadioRequest.Mode.USER_STORY, story, null));

        assertThat(script).isEqualTo(expected);
        assertThat(script.length()).isGreaterThan(250).isLessThanOrEqualTo(600);
        assertThat(providers.synthesize(script)).containsExactly(1, 2, 3);
        server.verify();
    }

    @Test
    void rejectsOversizedAiCommentBeforeTts() {
        expectCompletion("가".repeat(201));

        assertThatThrownBy(() -> providers.script(new RadioRequest(RadioRequest.Mode.USER_STORY, "사연", null)))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void rejectsEmptyAiCommentBeforeTts() {
        expectCompletion(" ");

        assertThatThrownBy(() -> providers.script(new RadioRequest(RadioRequest.Mode.USER_STORY, "사연", null)))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void randomBroadcastUsesGeneratedScriptWithoutStoryIntroduction() {
        String generated = "안녕하세요. 붕붕이 DJ입니다. 창밖 풍경을 즐겨 보세요. 즐거운 드라이브 되세요.";
        expectCompletion(generated);

        assertThat(providers.script(new RadioRequest(RadioRequest.Mode.RANDOM, null, null))).isEqualTo(generated);
        server.verify();
    }

    @Test
    void randomBroadcastKeepsItsLengthLimit() {
        expectCompletion("가".repeat(251));

        assertThatThrownBy(() -> providers.script(new RadioRequest(RadioRequest.Mode.RANDOM, null, null)))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    private void expectCompletion(String generated) {
        String response = json.writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("content", generated)))));
        server.expect(requestTo("https://api.groq.com/openai/v1/chat/completions"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }
}
