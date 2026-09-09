package com.npick.clip.application.command.register;

import java.time.LocalDate;
import java.util.List;

/**
 * 저장·검사를 마친 서버 내부 입력. 영상·처리 ID는 서버가 생성하고, registeredById는 인증 계층에서 채운다. 자막 키는 자막 접수 결과, 파이프라인 버전·단계는 활성 실행 정의에서 가져온다.
 */
public record RegisterClipCommand(
        String sourceType,
        String title,
        LocalDate broadcastDate,
        LocalDate filmedDate,
        String storageKey,
        String contentHash,
        String transcriptFileKey,
        String scriptText,
        long registeredById,
        long clipId,
        long pipelineRunId,
        String pipelineVersion,
        List<String> stageNames) {
    public RegisterClipCommand {
        stageNames = List.copyOf(stageNames);
    }
}
