package com.npick.search.application.query;

/**
 * 검색 실행이 남긴 결과 한 행 (S15P21A501-198).
 *
 * <p>{@code explainJson} 은 저장 당시 스냅샷 원문이다. 현재 태그·검색으로 다시 계산하지 않는다(FRD §7.2).
 */
public record SearchHistoryResultRow(
        long searchResultId, long sceneId, long clipId, int rank, String explainJson) {}
