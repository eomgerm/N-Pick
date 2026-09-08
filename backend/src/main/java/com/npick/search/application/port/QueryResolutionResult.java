package com.npick.search.application.port;

import java.util.List;

/**
 * 리졸버 호출 한 번의 결과 전체.
 *
 * <p>질의 리졸버 모듈의 {@code ResolutionResult} 에 대응한다. 버전이 셋인 이유는 셋 다 결과를 바꾸기 때문이다 — schema 가 바뀌면 필드가, 프롬프트가 바뀌면 해석이, 모델이 바뀌면
 * 판단이 달라진다. FRD v3.1 §7.2 가 "사용 설정과 규칙" 기록을 요구하므로 호출측이 그대로 저장한다.
 *
 * <p>{@code normalization_version} 은 여기 없다. 정규화는 해석과 별개로 같은 원문에서 출발하는 다른 호출이고, 그 버전은 정규화 결과에 실려 온다.
 */
public record QueryResolutionResult(
        QueryResolution resolution,
        List<AnchorFinding> findings,
        String resolutionSchemaVersion,
        String promptVersion,
        String modelVersion) {}
