package com.npick.search.application.query.execution;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchExecutionErrorCode;

@Service
public class SearchExecutionQueryService implements GetSearchExecutionUseCase {
    private final SearchExecutionDetailQueryPort executions;

    public SearchExecutionQueryService(SearchExecutionDetailQueryPort executions) {
        this.executions = executions;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SearchExecutionDetail get(long executionId, long requesterId, boolean reviewer) {
        return executions
                .findVisible(executionId, requesterId, reviewer)
                .orElseThrow(() -> new BusinessException(SearchExecutionErrorCode.NOT_FOUND));
    }
}
