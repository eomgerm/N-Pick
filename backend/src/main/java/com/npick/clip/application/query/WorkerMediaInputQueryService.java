package com.npick.clip.application.query;

import org.springframework.stereotype.Service;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.query.media.ClipStorageKeyQueryPort;
import com.npick.clip.application.query.media.GetWorkerMediaInputUseCase;
import com.npick.common.error.BusinessException;

@Service
public class WorkerMediaInputQueryService implements GetWorkerMediaInputUseCase {
    private final ClipStorageKeyQueryPort keys;
    private final MediaAssetPort assets;

    public WorkerMediaInputQueryService(ClipStorageKeyQueryPort keys, MediaAssetPort assets) {
        this.keys = keys;
        this.assets = assets;
    }

    public MediaInput get(long clipId) {
        String key = keys.findPlayableStorageKey(clipId)
                .orElseThrow(() -> new BusinessException(ClipMediaErrorCode.CLIP_NOT_FOUND));
        return new MediaInput(key, assets.resolve(key).sizeBytes());
    }
}
