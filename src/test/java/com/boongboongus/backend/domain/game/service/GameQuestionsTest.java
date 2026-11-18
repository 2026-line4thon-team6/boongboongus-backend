package com.boongboongus.backend.domain.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GameQuestionsTest {
    @ParameterizedTest
    @CsvSource({
            "킹콩, ㅋㅋ",
            "우하하, ㅇㅎㅎ",
            "언덕, ㅇㄷ",
            "천도, ㅊㄷ",
            "풋애플, ㅍㅇㅍ",
            "콜센터, ㅋㅅㅌ",
            "바이킹, ㅂㅇㅋ",
            "'풋 애플', ㅍㅇㅍ",
            "까치, ㄲㅊ",
            "가힣, ㄱㅎ"
    })
    void derivesHintFromAnswer(String answer, String expectedHint) {
        var question = GameQuestions.nonsense("테스트 문항", answer);

        assertThat(question.hint()).isEqualTo(expectedHint);
        assertThat(question.answer()).isEqualTo(answer);
    }
}
