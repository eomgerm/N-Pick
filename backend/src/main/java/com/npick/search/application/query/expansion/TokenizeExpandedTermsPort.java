package com.npick.search.application.query.expansion;

import java.util.List;

/**
 * 확장어를 후보 조회용 토큰으로 바꾼다 (`POST /query/tokenize`, S15P21A501-205).
 *
 * <p>백엔드에 Kiwi 가 없고, 교정({@code patch_parse})으로 검수자가 추가한 확장어는 리졸버가 모르는 값이라 해석 응답에 토큰을 실어 보내는 방식으로는 덮이지 않는다. 그래서 규칙 적용
 * <b>뒤</b>에 이 창구를 부른다.
 *
 * <p><b>실패는 degraded 가 아니다</b> (S15P21A501-48 계약 9). 확장어는 보조 신호이고, 한 건 때문에 검색을 끊거나 사용자에게 기능 누락을 알릴 일이 아니다. 구현은 실패하면 빈
 * 목록을 돌려준다.
 *
 * <h2>왜 평탄화하지 않는가 (S15P21A501-302)</h2>
 *
 * 예전 계약은 이 창구가 토큰을 한 목록으로 펼쳐 주는 것이었고, 그래서 후보 조회의 {@code paradedb.term_set} 이 확장어를 OR 로 받았다. 「중국 음식」이 {@code 중국} OR
 * {@code 음식} 이 되어 짜장면 검색에 중국 경제 뉴스가 올라왔다 (운영 실측 19건). 워커는 이미 항목별로 묶어서 주므로({@code tuple[tuple[str, ...]]}) 그 묶음을 그대로
 * 보존하고, 어댑터가 구마다 {@code must} 로 건다.
 *
 * <p>응답의 {@code matched_keywords} 는 종전대로 평탄화된 토큰 목록이다. 평탄화는 <b>호출부</b>가 근거 설명용으로 하며 후보 조회와는 별개다.
 *
 * <h2>불변식 — 쓸 수 있는 구만 돌려준다</h2>
 *
 * 빈 토큰이나 공백이 든 토큰이 하나라도 있는 구는 <b>통째로 빠진 채</b> 온다. 토큰만 빼고 남은 것으로 {@code must} 를 걸면 구가 헐거워져 「중국 음식」이 {@code 중국} 단독 매칭으로
 * 되돌아가기 때문이다.
 *
 * <p>거르는 자리가 후보 조회 어댑터가 아니라 <b>이 창구</b>인 이유는 호출부의 근거 설명 때문이다. 어댑터에서만 버리면 {@code SearchCandidatePipeline} 의 평탄화는 버려진 구의
 * 토큰을 여전히 확장어로 싣고, 실제로 후보를 만들지 않은 토큰이 {@code matched_keywords} 에 {@code origin=expanded} 로 뜬다 — 사용자가 치지 않은 말 때문에 결과가
 * 나왔다고 오해하게 되는, S15P21A501-234 가 막으려던 오표시다. 창구에서 걸러 두면 후보 조회와 근거 설명이 같은 구 목록을 본다.
 */
public interface TokenizeExpandedTermsPort {

    /**
     * @param terms 규칙 적용 후의 확장어. 빈 목록이면 호출하지 않는다
     * @param expectedNormalizationVersion 원 질의를 만든 정규화 버전. 워커 응답이 다른 버전이면 그 토큰을 쓰지 않는다 — 색인이 하지 않는 경계로 질의해 오류 없이 0건이 되기
     *     때문이다 (resolver-api §2.4-1)
     * @return 확장어 <b>항목별</b> 토큰 묶음. 입력 순서를 따르고, 토큰이 0개인 항목과 같은 묶음의 중복은 뺀다. 한 묶음 안의 중복 토큰도 뺀다. 원 질의 토큰과의 대조는 호출부가 한다. 계약
     *     불변식을 어긴 응답이면 빈 목록이다
     */
    List<List<String>> tokenize(List<String> terms, String expectedNormalizationVersion);
}
