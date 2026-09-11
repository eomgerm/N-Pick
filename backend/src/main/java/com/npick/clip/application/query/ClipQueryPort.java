package com.npick.clip.application.query;

import java.util.List;
import java.util.Optional;

public interface ClipQueryPort {
    List<ClipQueryResult> findPage(int offset, int size);

    long countVisible();

    Optional<ClipQueryResult> findVisible(long clipId);
}
