package com.npick.clip.application.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.npick.clip.application.command.activate.ActivateProcessedClipUseCase;
import com.npick.clip.application.port.ClipPublicationPort;

@Service
public class ClipPublicationService implements ActivateProcessedClipUseCase {
    private final ClipPublicationPort publication;

    public ClipPublicationService(ClipPublicationPort publication) {
        this.publication = publication;
    }

    @Override
    @Transactional
    public boolean activate(long clipId, long pipelineRunId, int processingNo) {
        var current = publication.lock(clipId, pipelineRunId, processingNo);
        if (current == null
                || !current.clip()
                        .canActivate(
                                processingNo, publication.mediaAvailable(current.storageKey()), current.resultsReady()))
            return false;
        publication.activate(clipId, pipelineRunId, current.transcriptSource());
        return true;
    }
}
