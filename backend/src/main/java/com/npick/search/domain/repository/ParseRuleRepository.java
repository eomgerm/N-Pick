package com.npick.search.domain.repository;

import java.util.List;

import com.npick.search.domain.model.ParseRule;

/**
 * 활성 해석 교정 규칙을 읽는다.
 *
 * <p>Aggregate 조회다 — Projection 이 아니라 규칙 <b>본문 전체</b>가 필요하다. {@code applied_rules_json} 에 본문 스냅샷을 남겨야 하고(§7.2) 조건 판정도
 * 본문으로 한다. 그래서 설계 정본 §9 의 QueryPort 가 아니라 Domain Repository 다.
 */
public interface ParseRuleRepository {

    /**
     * 적용할 수 있는 {@code patch_parse} 규칙 전부.
     *
     * <p>질의 지문으로 좁히지 않는다. {@code patch_parse} 의 매칭은 지문 일치가 아니라 AI 원본 해석의 조건 판정이고(F-05), 조건이 맞는지는 규칙 본문을 읽어야 알 수 있다.
     * 지문으로 미리 걸러내면 「표현이 달라도 같은 패턴이면 교정된다」가 성립하지 않는다.
     *
     * <p>검수자가 끈 규칙({@code active = false})은 오지 않는다. F-11 「검수자가 규칙을 끄면 이후 검색에서 적용하지 않는다」.
     *
     * <p><b>읽지 못한 규칙도 목록에 있다.</b> {@code condition_json}·{@code patch_json} 을 파싱하지 못한 행은 {@link ParseRule#unparsed} 로
     * 사유와 함께 온다. 빼고 오면 그 규칙이 오류 없이 조용히 안 걸린다 (§11).
     *
     * @throws com.npick.common.error.BusinessException 조회 자체가 실패한 경우. {@code SearchRuleErrorCode.RULE_LOOKUP_FAILED} 다.
     *     <b>빈 목록으로 대체하지 않는다</b> — 사람의 결정을 조용히 건너뛰지 않고 검색을 실패시켜야 한다 (§6.2)
     */
    List<ParseRule> findActivePatchParseRules();
}
