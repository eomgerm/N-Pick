package com.npick.search.domain.model;

/**
 * 검수자가 만든 비활성 장면 제외 후보. {@code search_rule} 의 {@code action='exclude_scene'}·{@code active=false} 행으로 저장된다
 * (S15P21A501-82, F-11).
 *
 * <p>patch_parse 후보와 달리 본문(condition/patch)이 없고, 원 신고 검색의 정규화 지문으로 매칭한다. 그래서 원 검색 실행의 지문·정규화 질의·필터·정규화 버전을 그대로 들고 온다
 * (baseline: exclude_scene 의 exact 조회 키 = query_fingerprint). 발화(-58)·검증(-83)·확정(-85)은 이 타입의 일이 아니다.
 *
 * @param sourceFeedbackId 원인 신고
 * @param requestKey 생성 멱등 키
 * @param targetSceneId 제외할 장면 (신고가 참조한 장면과 같아야 한다)
 * @param queryFingerprint 원 검색의 정규화 지문 (exact 매칭 키)
 * @param normalizedQuery 원 검색의 정규화 질의
 * @param normalizedFiltersJson 원 검색의 정규화 필터
 * @param normalizationVersion 원 검색의 정규화 버전
 */
public record SceneExcludeCandidate(
        long sourceFeedbackId,
        String requestKey,
        long targetSceneId,
        String queryFingerprint,
        String normalizedQuery,
        String normalizedFiltersJson,
        String normalizationVersion) {}
