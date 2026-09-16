package com.npick.clip.application.query;

import org.springframework.stereotype.Service;

import com.npick.clip.application.error.SceneThumbnailErrorCode;
import com.npick.clip.application.port.ThumbnailImagePort;
import com.npick.clip.application.query.thumbnail.GetSceneThumbnailUseCase;
import com.npick.clip.application.query.thumbnail.SceneKeyframeQueryPort;
import com.npick.clip.application.query.thumbnail.SceneKeyframeSource;
import com.npick.clip.application.query.thumbnail.SceneThumbnailResult;
import com.npick.common.error.BusinessException;

/**
 * 결과 카드·문의 목록의 장면 대표 이미지를 scene_id 로만 열어 준다 (FRD F-03·F-07).
 *
 * <p>단건 projection 조회와 파일 읽기뿐이라 트랜잭션을 두지 않는다(설계 정본 §5).
 *
 * <p>세 가지 실패를 여기서 나눈다. 장면 없음과 keyframe 없음은 이 계층이 판단하고, 파일 누락·경로 이탈은 Port 가 자기 어휘로 알려 온다. 화면이 「아직 처리 중입니다」 와 「이미지가
 * 사라졌습니다」 를 다르게 안내해야 하기 때문이다 (FRD §6.2).
 */
@Service
public class SceneThumbnailQueryService implements GetSceneThumbnailUseCase {

    private final SceneKeyframeQueryPort keyframes;
    private final ThumbnailImagePort images;

    public SceneThumbnailQueryService(SceneKeyframeQueryPort keyframes, ThumbnailImagePort images) {
        this.keyframes = keyframes;
        this.images = images;
    }

    @Override
    public SceneThumbnailResult get(long sceneId) {
        SceneKeyframeSource source = keyframes.findRepresentativeKeyframe(sceneId);
        if (!source.sceneFound()) {
            throw new BusinessException(SceneThumbnailErrorCode.SCENE_NOT_FOUND);
        }
        if (source.storageKey() == null) {
            throw new BusinessException(SceneThumbnailErrorCode.KEYFRAME_NOT_FOUND);
        }
        ThumbnailImagePort.ThumbnailImage image = images.read(source.storageKey());
        return new SceneThumbnailResult(image.contentType(), image.bytes());
    }
}
