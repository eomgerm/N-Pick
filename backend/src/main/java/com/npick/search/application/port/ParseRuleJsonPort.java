package com.npick.search.application.port;

import com.npick.search.domain.model.ParseRule;

/** 저장/API JSON 형태의 해석 규칙을 도메인 규칙으로 읽는 경계. application 은 Jackson 기반 mapper 구현을 알지 않는다. */
public interface ParseRuleJsonPort {

    ParseRule toDomain(long ruleId, String conditionJson, String patchJson);
}
