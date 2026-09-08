package com.npick.clip.application.port;

import java.math.BigDecimal;

import com.npick.clip.application.command.register.UploadClipCommand;

/** #35 구현: 형식·시간 구간을 검사하고 서버 소유 키로 접수한다. 임의 서버 경로를 입력으로 해석하지 않는다. */
public interface TranscriptIntakePort {
    Intake receive(UploadClipCommand.Subtitle subtitle, BigDecimal videoDuration, long clipId);

    interface Intake extends AutoCloseable {
        String storageKey();

        String contentHash();
        /** 등록 커밋 성공 또는 결과 불명확 시 보존한다. */
        void retain();
        /** retain 전에는 이번 접수 산출물을 정리한다. */
        void close();
    }
}
