package com.npick.search.application.query.execution;

import java.util.Optional;

public interface SearchExecutionDetailQueryPort {
    Optional<SearchExecutionDetail> findVisible(long executionId, long requesterId, boolean reviewer);
}
