package com.npick.feedback.application.query;

public record ExecutionSnapshot(
        String queryText,
        String parsedQueryJson,
        String resolverOutputJson,
        String appliedRulesJson,
        String appliedExcludesJson) {}
