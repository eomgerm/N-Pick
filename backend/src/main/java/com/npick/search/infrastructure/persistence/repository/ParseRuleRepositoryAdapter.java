package com.npick.search.infrastructure.persistence.repository;

import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchRuleErrorCode;
import com.npick.search.domain.model.ParseRule;
import com.npick.search.domain.repository.ParseRuleRepository;
import com.npick.search.infrastructure.persistence.mapper.ParseRuleJsonMapper;

/**
 * {@link ParseRuleRepository} 구현.
 *
 * <p>조회 실패를 {@link SearchRuleErrorCode#RULE_LOOKUP_FAILED} 로 번역하는 것이 이 어댑터의 두 번째 책임이다. <b>빈 목록으로 삼키지 않는다</b> — 그러면 검수자가
 * 승인한 교정이 없는 것처럼 검색이 성공하고, 사용자는 무엇이 빠졌는지 알 수 없다 (§6.2).
 */
@Repository
public class ParseRuleRepositoryAdapter implements ParseRuleRepository {

    /** {@code search_rule.action} 값. {@code exclude_scene} 는 {@code S15P21A501-58} 소관이다. */
    private static final String PATCH_PARSE = "patch_parse";

    private final SearchRuleJpaRepository jpaRepository;
    private final ParseRuleJsonMapper mapper;

    public ParseRuleRepositoryAdapter(SearchRuleJpaRepository jpaRepository, ParseRuleJsonMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public List<ParseRule> findActivePatchParseRules() {
        try {
            return jpaRepository.findByActionAndActiveIsTrueOrderBySearchRuleIdAsc(PATCH_PARSE).stream()
                    .map(e -> mapper.toDomain(e.searchRuleId(), e.conditionJson(), e.patchJson()))
                    .toList();
        } catch (DataAccessException ex) {
            throw new BusinessException(SearchRuleErrorCode.RULE_LOOKUP_FAILED, ex);
        }
    }
}
