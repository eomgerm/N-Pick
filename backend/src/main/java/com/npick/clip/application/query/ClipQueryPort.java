package com.npick.clip.application.query;

import java.util.List;
import java.util.Optional;

public interface ClipQueryPort {
    /** registeredById 가 null 이면 등록자를 가리지 않는다. */
    List<ClipQueryResult> findPage(int offset, int size, List<String> statuses, Long registeredById);

    java.util.Map<String, Long> countByLatestRunStatus(Long registeredById);

    Optional<ClipQueryResult> findVisible(long clipId);
}
