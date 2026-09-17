package com.npick.search.application.query.execution;

public interface GetSearchExecutionUseCase {
    SearchExecutionDetail get(long executionId, long requesterId, boolean reviewer);
}
