package com.npick.clip.domain.error;

import com.npick.common.error.ErrorCode;
import com.npick.common.error.ErrorType;

public enum ClipRegistrationErrorCode implements ErrorCode {
    INVALID_SOURCE_TYPE("CLIP_400_002", "영상 종류는 broadcast 또는 archive여야 합니다."),
    ARCHIVE_BROADCAST_DATE("CLIP_400_003", "자료 영상에는 방송일을 입력할 수 없습니다."),
    TITLE_TOO_LONG("CLIP_400_004", "제목은 50자 이내로 입력해 주세요."),
    TITLE_NOT_UTF8("CLIP_400_004", "제목을 UTF-8 로 읽지 못했습니다. 요청을 UTF-8 로 보내 주세요."),
    INVALID_DATE("CLIP_400_011", "날짜는 0001년부터 9999년 사이의 실제 날짜여야 합니다."),
    // 화면이 문구를 어느 입력 밑에 붙일지는 코드로 정한다. 그래서 날짜 종류마다 코드를 나눈다.
    FUTURE_BROADCAST_DATE("CLIP_400_013", "방송일은 등록일 이후 날짜일 수 없습니다."),
    BROADCAST_DATE_BEFORE_FILMED_DATE("CLIP_400_013", "방송일은 촬영일보다 빠를 수 없습니다."),
    FUTURE_FILMED_DATE("CLIP_400_014", "촬영일은 등록일 이후 날짜일 수 없습니다.");

    private final String code;
    private final String message;

    ClipRegistrationErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public ErrorType type() {
        return ErrorType.BAD_REQUEST;
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
