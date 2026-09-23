package com.npick.search.application.query.search;

/** 검증 결과에서 사람이 직접 확인할 장면의 재생·표시 정보. */
public record VerificationScene(
        long sceneId,
        long clipId,
        String clipTitle,
        String sceneDescription,
        long startTimeMs,
        long endTimeMs) {}
