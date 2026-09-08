package com.npick.feedback.application.query;

import java.time.Instant;

public record ReviewHistory(Long reviewedById, Instant reviewStartedAt, Long verifiedByExecutionId) {}
