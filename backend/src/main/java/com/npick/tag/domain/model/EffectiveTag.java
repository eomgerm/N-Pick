package com.npick.tag.domain.model;

/**
 * 지금 이 장면에서 유효한 태그 하나 (FRD v3.1 F-04·F-10).
 *
 * <p>검색의 네 곳(후보 추출·명시 필터 비교·구조화 축 점수·근거 설명)이 모두 이 값을 받는다. 각자 판정하면 후보에는 반영되고 점수에는 빠지는 식으로 어긋나 F-05 완료 기준 「태그 교정이 후보
 * 추출·필터·점수·설명에 일관되게 반영된다」 를 깬다.
 *
 * @param verification 검증 상태. 사람 판단과 자동 추출값을 혼동하지 않기 위해 셋으로 나눈다
 * @param scope 이 판정이 장면 자체에서 나왔는가, 클립 상속에서 나왔는가. F-10 의 두 적용 범위와 같은 어휘다
 * @param source 판정을 정한 근거의 {@code tag_evidence.source}. 결과 카드의 "출처" 가 이 값이다 (F-07)
 */
public record EffectiveTag(
        long sceneId,
        long clipId,
        long tagId,
        TagType tagType,
        String matchValue,
        String name,
        Verification verification,
        Scope scope,
        String source) {

    public enum Verification {

        /** 사람이 승인했다. 임의의 신뢰도 1.0 을 넣지 않으므로 확신도로 표현하지 않고 별도 상태로 둔다 (F-10 완료 기준). */
        REVIEWER_VERIFIED,

        /** 관측 근거가 검증됐다. 사용자 입력·원본 정보·CC·OCR. */
        VERIFIED,

        /** 미검증. ASR·VLM·일반 추론 규칙은 기본으로 여기 온다 (F-04). */
        UNVERIFIED;

        /**
         * 명시 조건과의 충돌 판정에 쓸 수 있는가 (F-06).
         *
         * <p>F-06 은 "명시한 날짜와 같은 종류의 <b>검증된</b> 날짜가 충돌" 할 때만 제외하고, "정보가 없거나 미검증" 은 그 이유만으로 제외하지 않는다. 그 경계가 이 한 줄이다.
         */
        public boolean trustedForConflict() {
            return this != UNVERIFIED;
        }
    }

    /** 판정이 나온 적용 범위. */
    public enum Scope {
        SCENE,
        CLIP
    }
}
