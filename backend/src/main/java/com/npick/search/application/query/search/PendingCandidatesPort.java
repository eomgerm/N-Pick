package com.npick.search.application.query.search;

public interface PendingCandidatesPort {
    PendingCandidates load(long feedbackId);
}
