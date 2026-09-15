package com.npick.tag.application.port;

/**
 * 태그 교정 후보 생성의 전제(S15P21A501-160). 대상 신고와 그 신고가 가리키는 장면·클립을 읽는다.
 *
 * <p>후보는 {@code reviewing} + {@code tag_correction} + 담당 검수자 조건에서만 만들 수 있다(F-09/F-10). 교정 범위는 이 신고의 장면 또는 클립으로 한정되므로,
 * 검수자는 임의의 장면·클립을 지정할 수 없다 — 범위는 SCENE/CLIP 둘 중 하나이고 실제 id 는 이 컨텍스트에서 온다.
 *
 * @param status 신고 상태
 * @param resolution 처리 결과 (tag_correction 등)
 * @param reviewedById 담당 검수자
 * @param sceneId 신고 장면
 * @param clipId 그 장면이 속한 클립
 */
public record TagContext(String status, String resolution, Long reviewedById, long sceneId, long clipId) {}
