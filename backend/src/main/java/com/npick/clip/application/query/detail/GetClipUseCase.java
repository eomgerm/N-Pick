package com.npick.clip.application.query.detail;

import com.npick.clip.application.query.ClipQueryResult;

public interface GetClipUseCase {
    ClipQueryResult getClip(long clipId);
}
