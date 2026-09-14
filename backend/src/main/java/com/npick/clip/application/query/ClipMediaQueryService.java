package com.npick.clip.application.query;

import org.springframework.stereotype.Service;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.query.media.ByteRangeSelection;
import com.npick.clip.application.query.media.ClipMediaStreamResult;
import com.npick.clip.application.query.media.ClipStorageKeyQueryPort;
import com.npick.clip.application.query.media.StreamClipMediaQuery;
import com.npick.clip.application.query.media.StreamClipMediaUseCase;
import com.npick.common.error.BusinessException;

/**
 * Preview 재생 대상을 ID 로만 열어 준다 (FR-RES-013~015).
 *
 * <p>단건 projection 조회와 파일 열기뿐이라 트랜잭션을 두지 않는다(설계 정본 §5).
 */
@Service
public class ClipMediaQueryService implements StreamClipMediaUseCase {

    private final ClipStorageKeyQueryPort storageKeys;
    private final MediaAssetPort assets;

    public ClipMediaQueryService(ClipStorageKeyQueryPort storageKeys, MediaAssetPort assets) {
        this.storageKeys = storageKeys;
        this.assets = assets;
    }

    @Override
    public ClipMediaStreamResult stream(StreamClipMediaQuery query) {
        String storageKey = storageKeys
                .findPlayableStorageKey(query.clipId())
                .orElseThrow(() -> new BusinessException(ClipMediaErrorCode.CLIP_NOT_FOUND));
        MediaAssetPort.MediaAsset asset = assets.resolve(storageKey);
        ByteRangeSelection selection = ByteRangeSelection.of(query.rangeHeader(), asset.sizeBytes());
        return new ClipMediaStreamResult(
                asset.contentType(),
                asset.sizeBytes(),
                selection.offset(),
                selection.length(),
                selection.partial(),
                asset.internalLocation().orElse(null),
                target -> asset.writeTo(target, selection.offset(), selection.length()));
    }
}
