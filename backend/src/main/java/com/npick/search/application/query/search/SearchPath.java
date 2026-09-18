package com.npick.search.application.query.search;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

import com.npick.search.application.port.StartSearchExecution.ExecutionType;
import com.npick.search.application.port.StartSearchExecution.ParseSource;

/**
 * 응답 시간을 나눠 재는 네 경로 (FRD §8.2 「일반·규칙 적용·대체 검색·후보 검증을 구분 측정」).
 *
 * <p>합쳐 재면 95% 10초 목표가 어느 경로 때문에 깨지는지 알 수 없다. 네 경로는 하는 일이 달라 분포도 다르다 — 대체 검색은 AI 호출이 없어 빠르고, 후보 검증은 트랜잭션 INSERT → 검색 →
 * ROLLBACK 이라 가장 느리다.
 *
 * <p>{@link #VERIFICATION} 은 <b>아직 값이 쌓이지 않는다.</b> 후보 검증 재검색(F-12)을 실행하는 유스케이스가 없어 이 경로로 들어오는 검색이 없다. 이름을 미리 두는 이유는 그
 * 유스케이스가 생겼을 때 측정 지표를 바꾸지 않고 태그만 붙이면 되게 하기 위해서다.
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

    /**
     * 실행 종류·해석 출처·규칙 적용 여부에서 경로를 정한다.
     *
     * <p>규칙 적용을 {@code parseSource} 가 아니라 별도 인자로 받는 이유는 {@link ParseSource} 에 {@code resolver_rule} 값이 없기 때문이다.
     * baseline COLUMN COMMENT 는 그 값을 정의해 두었지만 현재 enum 은 두 값뿐이라, 규칙이 걸린 검색과 아닌 검색이 같은 출처로 기록된다. 측정까지 그 한계를 물려받으면 §8.2 의
     * 「규칙 적용」 경로가 영영 비므로 여기서는 적용 사실을 직접 받는다.
     */
    public static SearchPath of(ExecutionType executionType, ParseSource parseSource, boolean appliedRule) {
        if (executionType == ExecutionType.REPLAY) {
            return VERIFICATION;
        }
        if (parseSource == ParseSource.FALLBACK) {
            return FALLBACK;
        }
        return appliedRule ? PATCHED : NORMAL;
    }
}
