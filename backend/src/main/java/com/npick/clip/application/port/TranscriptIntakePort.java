package com.npick.clip.application.port;

import java.math.BigDecimal;

import com.npick.clip.application.command.register.UploadClipCommand;

/**
 * #35 구현 계약: 형식·시간 구간을 검사하고 요청별 서버 소유 임시 산출물로 접수한다. 파일명은 표시 정보이며 임의 서버 경로로 해석하지 않는다. 중복 판정 전에 호출되므로 이 단계에서 기존 영상/자막을
 * 덮어쓰거나 clip·pipeline_run을 저장하지 않는다.
 */
public interface TranscriptIntakePort {
    Intake receive(UploadClipCommand.Subtitle subtitle, BigDecimal videoDuration, long clipId);

    interface Intake extends AutoCloseable {
        /** 서버가 생성한 상대 키. 절대 경로·상위 경로 이동을 포함하지 않으며 이번 접수만 소유한다. */
        String storageKey();

        /** 업로드한 원본 바이트 전체의 SHA-256 소문자 hex 64자. 파싱·정규화된 텍스트의 해시가 아니다. */
        String contentHash();
        /** 등록 커밋 성공 또는 결과 불명확 시 보존한다. 멱등적인 메모리 상태 전환이며 I/O나 예외를 발생시키지 않는다. */
        void retain();
        /**
         * retain 전에는 소유권을 확인하여 이번 접수 산출물만 정리한다. 중복 응답도 이 경로를 사용한다. retain 후 또는 이미 정리된 경우에는 멱등적인 no-op이다. 정리 오류에 원문·서버
         * 경로를 노출하지 않는다.
         */
        void close();
    }
}
