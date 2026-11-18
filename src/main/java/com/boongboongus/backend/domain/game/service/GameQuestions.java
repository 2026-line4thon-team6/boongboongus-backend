package com.boongboongus.backend.domain.game.service;

import com.boongboongus.backend.domain.game.repository.GameRepository.Seed;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GameQuestions {
    static final int COUNT = 5;

    private static final List<Seed> BALANCE = List.of(
            balance("드라이브에서 한 곳만 갈 수 있다면?", "바닷가", "야경 명소"),
            balance("여행에서 더 끌리는 쪽은?", "계획대로 돌아보기", "마음 가는 대로 둘러보기"),
            balance("휴게소에서 하나만 먹는다면?", "따뜻한 우동", "갓 만든 호두과자"),
            balance("차 안에서 하나만 들을 수 있다면?", "좋아하는 노래 반복", "처음 듣는 노래만"),
            balance("같은 장소에 다시 간다면?", "일출 시간", "일몰 시간"),
            balance("쉬어 가고 싶은 곳은?", "조용한 숲길", "한적한 강변"),
            balance("여행을 한 가지로 남긴다면?", "사진 한 장", "짧은 일기"));
    private static final List<Seed> NONSENSE = List.of(
            nonsense("왕이 넘어지면?", "킹콩"),
            nonsense("소가 웃으면?", "우하하"),
            nonsense("오리가 얼면?", "언덕"),
            nonsense("세상에서 가장 뜨거운 과일은?", "천도"),
            nonsense("사과가 웃으면?", "풋애플"),
            nonsense("전화기로 지은 건물은?", "콜센터"),
            nonsense("왕이 헤어질 때 하는 인사는?", "바이킹"));
    private static final List<String> MEMORY_PROMPTS = List.of(
            "‘%s’에서 누가 먼저 다시 가자고 할 것 같나요?",
            "‘%s’에서 누가 더 신나게 노래를 골랐을까요?",
            "‘%s’에서 누가 더 오래 풍경을 기억할 것 같나요?",
            "‘%s’에서 누가 더 먼저 쉬어 가자고 했을 것 같나요?",
            "‘%s’를 떠올리며 누가 먼저 사진을 찾아볼 것 같나요?");

    private GameQuestions() {
    }

    static List<Seed> balance() {
        return randomSet(BALANCE);
    }

    static List<Seed> nonsense() {
        return randomSet(NONSENSE);
    }

    static List<Seed> memories(List<String> titles) {
        if (titles.isEmpty()) {
            return List.of();
        }
        List<Seed> questions = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; i++) {
            String prompt = MEMORY_PROMPTS.get(i).formatted(titles.get(i % titles.size()));
            questions.add(balance(prompt, "운전자", "동승자"));
        }
        return questions;
    }

    private static List<Seed> randomSet(List<Seed> source) {
        var shuffled = new ArrayList<>(source);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, COUNT);
    }

    private static Seed balance(String prompt, String a, String b) {
        return new Seed(prompt, a, b, null, null);
    }

    static Seed nonsense(String prompt, String answer) {
        String initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
        var hint = new StringBuilder();
        for (int codePoint : answer.codePoints().toArray()) {
            if (codePoint >= '가' && codePoint <= '힣') {
                // Each Hangul initial spans 21 vowels and 28 final consonants.
                hint.append(initials.charAt((codePoint - '가') / (21 * 28)));
            } else if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                hint.appendCodePoint(codePoint);
            }
        }
        return new Seed(prompt, null, null, hint.toString(), answer);
    }
}
