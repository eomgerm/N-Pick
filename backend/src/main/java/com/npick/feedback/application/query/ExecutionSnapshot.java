package com.npick.feedback.application.query;

public record ExecutionSnapshot(
        String queryText,
        String explicitFiltersJson,
        String parsedQueryJson,
        String resolverOutputJson,
        String appliedRulesJson,
        String appliedExcludesJson) {}
