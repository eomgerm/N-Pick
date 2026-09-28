package com.npick.search.application.query.search;

import java.util.List;

import com.npick.search.application.port.QueryResolutionResult;
import com.npick.search.application.port.StartSearchExecution.ParseSource;
import com.npick.search.application.resolution.SearchDegradedReason;
import com.npick.search.domain.model.NormalizedSearch;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.policy.ParseRulePolicy;

/**
 * 검색어 해석이 끝난 상태 — 후보를 찾기 직전까지.
 *
 * <p>기록을 <b>하나도 건드리지 않는다.</b> 후보 검증 재검색(F-12, S15P21A501-83)이 롤백 트랜잭션 안에서 같은 해석을 태워야 하는데, 기록 호출이 {@code REQUIRES_NEW} 라
 * 롤백 밖에서 커밋되기 때문이다. 그대로 부르면 롤백돼야 할 검증 검색이 {@code execution_type='original'} 행을 남겨 일반 검색·평가 집계에 섞인다.
 *
 * <p>이 자리가 없으면 -83 이 해석 글루를 복제해야 하고, FRD §11 의 「일반 검색과 같은 코드로 검증 검색을 한다」가 깨진다.
 *
 * @param raw 리졸버가 준 그대로. {@code AnchorVerifier} 통과 전이며 §7.2 의 「교정 전 AI 해석」이다
 * @param resolved 검증 통과본. 이 값의 anchor {@code origin} 에 강등 결과가 들어 있다
 * @param appliedRule 승인 규칙이 하나라도 적용됐는가. {@code applied_rules_json} 의 {@code applied} 존재와 같은 판정이다
 * @param finalResolution 규칙·명시 필터까지 적용한 실제 사용 해석. 해석 실패여도 {@code null} 이 아니다
 * @param degradedReasons 해석 단계까지 확정된 사유. 후보 구간이 낸 사유는 아직 들어 있지 않다
 */
public record InterpretedQuery(
        QueryResolutionResult raw,
        QueryResolutionResult resolved,
        int parseMs,
        ParseRulePolicy.Result rules,
        boolean appliedRule,
        ParseSource parseSource,
        QueryResolution finalResolution,
        NormalizedSearch normalizedSearch,
        List<SearchDegradedReason> degradedReasons) {

    public InterpretedQuery {
        degradedReasons = List.copyOf(degradedReasons);
    }
}
