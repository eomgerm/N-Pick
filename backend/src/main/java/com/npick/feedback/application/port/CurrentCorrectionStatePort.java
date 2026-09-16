package com.npick.feedback.application.port;

/**
 * 확정 시점의 현재 상태 지문을 계산한다 (S15P21A501-84, F-13 3·4).
 *
 * <p>검증 실행에 저장된 지문과 비교해 「검증 이후 관련 상태가 바뀌었는지」를 판정한다. 지문에 무엇을 넣을지(대상 태그·근거·상속·처리결과, 활성 규칙 집합, 출력 계약·검색 설정)는 검증 실행을
 * 만드는 쪽(S15P21A501-83)이 정하고, 확정은 같은 방식으로 계산한 현재 지문을 같은지 비교만 한다.
 */
public interface CurrentCorrectionStatePort {
    String currentFingerprint(long feedbackId);
}
