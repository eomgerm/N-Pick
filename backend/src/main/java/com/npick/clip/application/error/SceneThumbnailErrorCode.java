package com.npick.clip.application.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

/**
 * 장면 대표 이미지 조회(FRD F-03·F-07)의 실패 어휘.
 *
 * <p>재생(FR-RES-013~015)의 {@link ClipMediaErrorCode} 와 나눈 이유는 실패의 의미가 다르기 때문이다. 재생은 "이 클립을 못 연다" 하나지만 썸네일은 장면이 없는 것과,
 * 장면은 있는데 AI 가 아직 keyframe 을 남기지 않은 것과, 행은 있는데 파일이 사라진 것이 서로 다른 안내로 이어진다 — 앞의 둘은 처리 진행 상황이고 뒤의 하나는 저장소 사고다.
 */
public enum SceneThumbnailErrorCode implements ErrorCode {
    SCENE_NOT_FOUND(ErrorType.NOT_FOUND, "SCENE_404_001", "장면을 찾을 수 없습니다."),
    KEYFRAME_NOT_FOUND(ErrorType.NOT_FOUND, "SCENE_404_002", "장면의 대표 이미지가 아직 없습니다."),
    THUMBNAIL_FILE_MISSING(ErrorType.NOT_FOUND, "SCENE_404_003", "대표 이미지 파일이 없어 표시할 수 없습니다."),
    THUMBNAIL_LOCATION_REJECTED(ErrorType.INTERNAL_SERVER_ERROR, "SCENE_500_001", "대표 이미지 저장 위치가 올바르지 않습니다."),
    THUMBNAIL_READ_FAILED(ErrorType.SERVICE_UNAVAILABLE, "SCENE_503_001", "대표 이미지를 불러오지 못했습니다."),
    MEDIA_ROOT_UNAVAILABLE(ErrorType.SERVICE_UNAVAILABLE, "SCENE_503_002", "대표 이미지 저장 위치 설정이 준비되지 않았습니다.");

    private final ErrorType type;
    private final String code;
    private final String message;

    SceneThumbnailErrorCode(ErrorType type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return type;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
