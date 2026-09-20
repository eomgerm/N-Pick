package com.npick.search.application.query.search;

import java.util.List;

public interface VerificationInputPort {
    VerificationInput load(long feedbackId);

    /** 이 신고의 원 실행이 낸 모든 결과 장면(rank 순), diff 의 원 집합이 된다 (F-12 4). */
    List<Long> loadOriginalResultSceneIds(long feedbackId);
}
