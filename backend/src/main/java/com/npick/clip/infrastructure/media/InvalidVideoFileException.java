package com.npick.clip.infrastructure.media;

import java.io.IOException;

/** Adapter 내부에서 파일 자체의 검사 실패와 실행 환경의 I/O 실패를 구분한다. */
final class InvalidVideoFileException extends IOException {
    InvalidVideoFileException(String message) {
        super(message);
    }
}
