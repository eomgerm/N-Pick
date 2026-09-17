package com.npick.clip.infrastructure.worker;

import org.springframework.stereotype.Component;

import com.npick.clip.application.query.media.GetWorkerMediaInputUseCase;
import com.npick.pipeline.application.port.ClipMediaInputPort;

/** {@link ClipMediaInputPort} 구현 (S15P21A501-201). 조회 자체는 clip 의 UseCase 하나를 그대로 지난다. */
@Component
public class WorkerClipMediaAdapter implements ClipMediaInputPort {
    private final GetWorkerMediaInputUseCase media;

    public WorkerClipMediaAdapter(GetWorkerMediaInputUseCase media) {
        this.media = media;
    }

    @Override
    public MediaInput get(long clipId) {
        var source = media.get(clipId);
        return new MediaInput(source.storageKey(), source.sizeBytes());
    }
}
