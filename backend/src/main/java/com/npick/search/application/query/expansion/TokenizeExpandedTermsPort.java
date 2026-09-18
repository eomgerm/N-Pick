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
 */
public interface TokenizeExpandedTermsPort {

    /**
     * @param terms 규칙 적용 후의 확장어. 빈 목록이면 호출하지 않는다
     * @return 입력 순서와 무관하게 <b>펼쳐서 중복을 뺀</b> 토큰. 원 질의 토큰과의 중복 제거는 호출부가 한다
     */
    List<String> tokenize(List<String> terms);
}
