package com.npick.pipeline.application.port;

import java.util.Map;

/** #70의 단계별 schema 검증·artifact 검증·정본 저장 연결점. complete 트랜잭션에 참여한다. */
public interface StageOutputPort {
    /** Stages whose successful output this adapter can validate and persist. */
    default boolean supports(String stage) {
        return false;
    }

    /** 전체 결과를 검증한 뒤 해당 run의 정본만 저장하고 assignedIds를 반환한다. */
    Map<String, Object> validateAndStore(
            long runId, long clipId, String stage, String outputKeyPrefix, Map<String, Object> result);
}
