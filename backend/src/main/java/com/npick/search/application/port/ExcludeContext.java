package com.npick.search.application.port;

/**
 * 장면 제외 후보 생성의 전제. 대상 신고와 그 신고가 가리키는 원 검색 실행에서 읽는다 (S15P21A501-82).
 *
 * <p>후보는 {@code reviewing} + {@code exclude_scene} + 담당 검수자 조건에서만 만들 수 있고(F-09/F-11), 대상은 신고가 참조한 장면과 같아야 한다. 매칭 키는 원
 * 검색의 정규화 지문이므로 그 값들을 함께 읽는다.
 *
 * @param status 신고 상태 (OPEN/REVIEWING/CLOSED)
 * @param resolution 처리 결과 (exclude_scene 등). 판정 전이면 {@code null}
 * @param reviewedById 담당 검수자. claim 전이면 {@code null}
 * @param sceneId 신고가 참조한 장면. 제외 대상은 이 장면이어야 한다
 * @param queryFingerprint 원 검색의 정규화 지문 (exclude exact 매칭 키)
 * @param normalizedQuery 원 검색의 정규화 질의
 * @param normalizedFiltersJson 원 검색의 정규화 필터
 * @param normalizationVersion 원 검색의 정규화 버전
 */
public record ExcludeContext(
        String status,
        String resolution,
        Long reviewedById,
        long sceneId,
        String queryFingerprint,
        String normalizedQuery,
        String normalizedFiltersJson,
        String normalizationVersion) {}
