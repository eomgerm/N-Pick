package com.npick.clip.application.command.prepare;

public interface PrepareStoredTranscriptInputUseCase {
    PrepareTranscriptInputUseCase.Prepared prepare(long clipId, long runId, String outputKeyPrefix);
}
