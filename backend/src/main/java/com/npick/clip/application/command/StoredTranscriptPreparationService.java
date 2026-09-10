package com.npick.clip.application.command;

import org.springframework.stereotype.Service;

import com.npick.clip.application.command.prepare.PrepareStoredTranscriptInputUseCase;
import com.npick.clip.application.command.prepare.PrepareTranscriptInputUseCase;
import com.npick.clip.application.port.TranscriptPreparationSourcePort;

/** DB 조회가 끝난 뒤 미디어를 검사·추출한다. 긴 I/O에 관계형 트랜잭션을 열지 않는다. */
@Service
public class StoredTranscriptPreparationService implements PrepareStoredTranscriptInputUseCase {
    private final TranscriptPreparationSourcePort sources;
    private final PrepareTranscriptInputUseCase preparation;

    public StoredTranscriptPreparationService(
            TranscriptPreparationSourcePort sources, PrepareTranscriptInputUseCase preparation) {
        this.sources = sources;
        this.preparation = preparation;
    }

    public PrepareTranscriptInputUseCase.Prepared prepare(long clipId, long runId, String prefix) {
        var source = sources.load(clipId, runId);
        return preparation.prepare(new PrepareTranscriptInputUseCase.Command(
                source.videoStorageKey(), source.transcriptFileKey(), source.videoDuration(), prefix));
    }
}
