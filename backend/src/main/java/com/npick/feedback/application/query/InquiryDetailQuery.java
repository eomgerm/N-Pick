package com.npick.feedback.application.query;

import java.util.Optional;

public interface InquiryDetailQuery {
    Optional<InquiryDetail> findById(long feedbackId);
}
