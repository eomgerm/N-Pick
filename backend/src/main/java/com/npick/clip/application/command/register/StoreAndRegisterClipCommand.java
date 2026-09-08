package com.npick.clip.application.command.register;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.npick.clip.application.command.prepare.PrepareVideoResult;

/** 검증 결과의 수명은 호출자가 관리한다. ID·등록자·실행 정의는 서버가 채운다. */
public record StoreAndRegisterClipCommand(
        PrepareVideoResult video,
        long clipId,
        long pipelineRunId,
        String sourceType,
        String title,
        LocalDate broadcastDate,
        LocalDate filmedDate,
        String transcriptFileKey,
        String scriptText,
        long registeredById,
        String pipelineVersion,
        List<String> stageNames) {
    public StoreAndRegisterClipCommand {
        Objects.requireNonNull(video, "video");
        stageNames = List.copyOf(stageNames);
    }
}
