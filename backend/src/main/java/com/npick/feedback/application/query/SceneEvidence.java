package com.npick.feedback.application.query;

import java.util.List;

/**
 * 문의 상세의 「현재 태그」 한 줄. tagging(태그 적용) 단위이며, 한 태그에 확정 근거가 여러 건이면
 * {@code sources} 에 모으고 {@code verifiedState} 는 verified 우선으로 하나만 낸다 (S15P21A501-235).
 * {@code tagType} 과 {@code matchValue} 는 표시 이름과 별개인 교정 후보 식별값이다 (S15P21A501-268).
 */
public record SceneEvidence(
        long taggingId,
        String tagType,
        String matchValue,
        String tagName,
        List<String> sources,
        String verifiedState,
        String scope) {}
