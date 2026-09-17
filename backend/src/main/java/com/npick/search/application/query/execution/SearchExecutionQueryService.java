package com.npick.search.application.query.execution;

import org.springframework.stereotype.Service;

import com.npick.common.error.BusinessException;
import com.npick.search.application.error.SearchExecutionErrorCode;

@Service
public class SearchExecutionQueryService implements GetSearchExecutionUseCase {
    private final SearchExecutionDetailQueryPort executions;

    public SearchExecutionQueryService(SearchExecutionDetailQueryPort executions) {
        this.executions = executions;
    }

    @Override
    public SearchExecutionDetail get(long executionId, long requesterId, boolean reviewer) {
        return executions
                .findVisible(executionId, requesterId, reviewer)
                .orElseThrow(() -> new BusinessException(SearchExecutionErrorCode.NOT_FOUND));
    }
}
