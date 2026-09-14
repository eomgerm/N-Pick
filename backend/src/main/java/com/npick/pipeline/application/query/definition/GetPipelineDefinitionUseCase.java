package com.npick.pipeline.application.query.definition;

import java.util.List;
import java.util.Map;

public interface GetPipelineDefinitionUseCase {
    Definition get();

    record Definition(String version, List<String> stages, Map<String, String> stageVersions) {
        public Definition {
            stages = List.copyOf(stages);
            stageVersions = Map.copyOf(stageVersions);
        }
    }
}
