package com.npick.search.application.query.search;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.search.application.port.SearchExecutionRecordPort.ExecutionType;
import com.npick.search.application.port.SearchExecutionRecordPort.ParseSource;

/**
 * 응답 시간을 나눠 재는 네 경로 (FRD §8.2 「일반·규칙 적용·대체 검색·후보 검증을 구분 측정」).
 *
 * <p>합쳐 재면 95% 10초 목표가 어느 경로 때문에 깨지는지 알 수 없다. 네 경로는 하는 일이 달라 분포도 다르다 — 대체 검색은 AI 호출이 없어 빠르고, 후보 검증은 트랜잭션
 * INSERT → 검색 → ROLLBACK 이라 가장 느리다.
 *
 * <p>{@link #VERIFICATION} 은 <b>아직 값이 쌓이지 않는다.</b> 후보 검증 재검색(F-12)을 실행하는 유스케이스가 없어 이 경로로 들어오는 검색이 없다. 이름을 미리
 * 두는 이유는 그 유스케이스가 생겼을 때 측정 지표를 바꾸지 않고 태그만 붙이면 되게 하기 위해서다.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum SearchPath {
    /** AI 해석은 됐고 승인 규칙은 걸리지 않은 보통 검색. */
    NORMAL("normal"),
    /** 승인된 해석 규칙을 1개 이상 적용한 검색. */
    PATCHED("patched"),
    /** AI 해석 없이 원 검색어 토큰으로만 돈 검색 (§6.2). */
    FALLBACK("fallback"),
    /** 교정 후보를 임시 반영해 돌린 검증 검색 (F-12). */
    VERIFICATION("verification");

    private final String tag;

    /** 실행 종류와 해석 출처에서 경로를 정한다. 두 값은 같은 실행의 기록에도 그대로 들어가므로 측정과 기록이 갈리지 않는다. */
    public static SearchPath of(ExecutionType executionType, ParseSource parseSource) {
        if (executionType == ExecutionType.REPLAY) {
            return VERIFICATION;
        }
        return switch (parseSource) {
            case FALLBACK -> FALLBACK;
            case RESOLVER_RULE -> PATCHED;
            case RESOLVER -> NORMAL;
        };
    }
}
