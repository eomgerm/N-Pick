package com.npick.search.application.query.card;

import java.util.List;

/**
 * 결과 카드 한 장이 DB 에서만 얻을 수 있는 값 — 검색 <b>당시</b>의 표시값이다.
 *
 * <p>여기 없는 카드 필드는 태그에서 온다. 방송일·촬영일과 그 검증 상태, {@code scene_type}, 태그 근거는 {@code EffectiveTag} 가 들고 오므로 이 포트가 중복해서 읽지 않는다
 * ({@code ResolveSceneTagsUseCase}).
 *
 * <p>토큰 세 가지를 함께 싣는 이유는 {@code matched_keywords} 와 {@code match_evidence} 의 {@code field} 를 조립이 정해야 하기 때문이다. 어떤 질의 토큰이
 * 설명에서 걸렸는지 대사에서 걸렸는지는 점수 채널(-51)이 알려 주지 않는다 — 그쪽은 장면당 점수 하나만 돌려준다.
 *
 * <p>이 값들은 {@code search_result.explain_json} 에 그대로 박혀 과거 결과의 근거가 된다. 지금의 교정 상태로 다시 계산해 덮어쓰지 않는다 (FRD §7.2).
 *
 * @param clipTitle {@code clip.title}. nullable 이다 — 대체 표기는 표현 계층이 정하고, 기록에는 없었다는 사실이 그대로 남는다
 * @param caption 장면 설명. {@code scene_description} 으로 나간다
 * @param shotType 저장값 그대로. 닫힌 4값 밖이면 표현 계층이 {@code unknown} 으로 떨어뜨린다
 */
public record SceneCard(
        long sceneId,
        long clipId,
        String clipTitle,
        String caption,
        long startTimeMs,
        long endTimeMs,
        String shotType,
        List<String> captionTokens,
        String transcriptText,
        List<String> transcriptTokens,
        List<OcrText> ocrTexts) {

    public SceneCard {
        captionTokens = List.copyOf(captionTokens);
        transcriptTokens = List.copyOf(transcriptTokens);
        ocrTexts = List.copyOf(ocrTexts);
    }

    /**
     * 한 장면에 걸린 화면 글자 관측 하나.
     *
     * @param rawText {@code match_evidence.value} 로 나가는 원문
     * @param tokens 질의 토큰과 맞춰 볼 색인 토큰
     */
    public record OcrText(String rawText, List<String> tokens) {
        public OcrText {
            tokens = List.copyOf(tokens);
        }
    }
}
