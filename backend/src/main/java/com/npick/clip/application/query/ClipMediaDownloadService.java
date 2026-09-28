package com.npick.clip.application.query;

import org.springframework.stereotype.Service;

import com.npick.clip.application.error.ClipMediaErrorCode;
import com.npick.clip.application.port.MediaAssetPort;
import com.npick.clip.application.port.MediaSegmentPort;
import com.npick.clip.application.query.media.ClipMediaDownloadResult;
import com.npick.clip.application.query.media.ClipStorageKeyQueryPort;
import com.npick.clip.application.query.media.DownloadClipMediaUseCase;
import com.npick.clip.application.query.media.DownloadSceneMediaUseCase;
import com.npick.clip.application.query.media.SceneMediaSource;
import com.npick.clip.application.query.media.SceneMediaSourceQueryPort;
import com.npick.common.error.BusinessException;

/** 원본 클립과 저장된 장면 경계를 다운로드 파일로 연다. */
@Service
public class ClipMediaDownloadService implements DownloadClipMediaUseCase, DownloadSceneMediaUseCase {

    private final ClipStorageKeyQueryPort clipSources;
    private final SceneMediaSourceQueryPort sceneSources;
    private final MediaAssetPort assets;
    private final MediaSegmentPort segments;

    public ClipMediaDownloadService(
            ClipStorageKeyQueryPort clipSources,
            SceneMediaSourceQueryPort sceneSources,
            MediaAssetPort assets,
            MediaSegmentPort segments) {
        this.clipSources = clipSources;
        this.sceneSources = sceneSources;
        this.assets = assets;
        this.segments = segments;
    }

    @Override
    public ClipMediaDownloadResult downloadClip(long clipId) {
        String storageKey = clipSources
                .findPlayableStorageKey(clipId)
                .orElseThrow(() -> new BusinessException(ClipMediaErrorCode.CLIP_NOT_FOUND));
        return result("clip-" + clipId, assets.resolve(storageKey));
    }

    @Override
    public ClipMediaDownloadResult downloadScene(long sceneId) {
        SceneMediaSource source = sceneSources
                .findDownloadSource(sceneId)
                .orElseThrow(() -> new BusinessException(ClipMediaErrorCode.SCENE_NOT_FOUND));
        if (source.startTimeMs() < 0 || source.endTimeMs() <= source.startTimeMs()) {
            throw new BusinessException(ClipMediaErrorCode.MEDIA_EXTRACTION_FAILED);
        }
        return result(
                "scene-" + sceneId, segments.extract(source.storageKey(), source.startTimeMs(), source.endTimeMs()));
    }

    private static ClipMediaDownloadResult result(String baseName, MediaAssetPort.MediaAsset asset) {
        String extension = "video/quicktime".equals(asset.contentType()) ? ".mov" : ".mp4";
        return new ClipMediaDownloadResult(
                baseName + extension,
                asset.contentType(),
                asset.sizeBytes(),
                asset.internalLocation().orElse(null),
                new com.npick.clip.application.query.media.ClipMediaBody() {
                    @Override
                    public void writeTo(java.io.OutputStream target) {
                        asset.writeTo(target, 0, asset.sizeBytes());
                    }

                    @Override
                    public void close() {
                        asset.close();
                    }
                });
    }
}
