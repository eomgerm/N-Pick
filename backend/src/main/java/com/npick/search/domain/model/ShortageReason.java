package com.npick.search.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 결과가 10개에 못 미친 이유 (web-api §5.1 「결과가 10개 미만이면 {@code shortage_reasons} 가 1개 이상이어야 한다」).
 *
 * <p>「부족하다」와 「검색이 실패했다」는 다르다. 실패는 결과 0건의 성공 응답으로 위장하지 않고 오류로 나간다 (FRD F-06 완료 기준). 여기 값들은 <b>정상적으로 돌았는데 채울 게
 * 없었던</b> 경우만 설명한다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum ShortageReason {
    /** 후보를 다 써도 10개가 안 됐다. 색인에 맞는 장면이 그만큼뿐이다. */
    CANDIDATE_POOL_EXHAUSTED("candidate_pool_exhausted"),
    /** 후보는 있었으나 guard·장면 제외가 걷어내 10개를 못 채웠다. */
    GUARD_EXCLUDED("guard_excluded");

    private final String wireValue;
}
