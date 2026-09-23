package com.npick.search.application.query.search;

import java.util.List;

public interface VerificationInputPort {
    VerificationInput load(long feedbackId);

    /** 이 신고의 원 실행이 낸 모든 결과 장면(rank 순). diff 와 빠진 장면 재생 정보에 함께 쓴다 (F-12 4). */
    List<VerificationScene> loadOriginalResultScenes(long feedbackId);
}
