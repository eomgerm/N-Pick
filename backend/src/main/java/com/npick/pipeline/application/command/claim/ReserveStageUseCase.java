package com.npick.pipeline.application.command.claim;

import java.util.Map;
import java.util.Optional;

/** 내부 배정 트랜잭션. 외부 호출자는 입력 준비까지 수행하는 ClaimStageUseCase를 사용한다. */
public interface ReserveStageUseCase {
    Optional<Map<String, Object>> reserve(ClaimStageCommand command);
}
