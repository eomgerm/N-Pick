package com.npick.pipeline.application.query;

import java.util.List;
import java.util.Map;

public interface GetProcessingProgressUseCase {
    Map<Long, ProcessingProgressResult> getProgress(List<Long> runIds);
}
