package com.npick.search.application.port;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * 검색 한 번을 {@code search_execution} 과 {@code search_result} 에 남긴다 (FRD §7.2, F-05 7항).
 *
 * <p><b>구현은 S15P21A501-60 이다.</b> 조립(-59)은 부르는 쪽만 소유한다 — 언제·무엇을 넘기는지는 여기, 어느 컬럼에 어떻게 쓰는지는 저쪽이다.
 *
 * <p>호출 규약: 한 검색당 {@link #start} 1회, 그 뒤 {@link #complete} 최대 1회. {@code start} 가 던지면 {@code complete} 는 부르지 않는다.
 * 재시도·재호출은 없다 — 같은 검색을 두 실행으로 남기지 않기 위해서다 (§7.2 「매번 새 실행을 만든다」의 반대쪽).
 *
 * <p>두 호출은 검색 조회 트랜잭션 <b>밖</b>에서 각각 커밋되어야 한다. 후보 검증(F-12)은 임시 반영→검색→ROLLBACK 으로 도는데, 기록이 같은 트랜잭션에 묶이면 롤백과 함께
 * 사라진다 (baseline 주석).
 */
public interface SearchExecutionRecordPort {

    /**
     * 실행을 열고 식별자를 받는다.
     *
     * @return {@code search_execution_id}
     * @throws SearchRecordingException 저장 실패. 조립은 §6.2 「검색 실행의 최초 저장 실패」로 검색을 중단한다
     */
    long start(StartSearchExecution command);

    /**
     * 결과를 확정해 닫는다.
     *
     * @return {@code rankedScenes} 와 <b>같은 순서·같은 길이</b>의 {@code search_result_id}
     * @throws SearchRecordingException 저장 실패. 조립은 계산된 결과를 미저장 상태로 내보낸다 (§6.2·web-api §5.1 {@code snapshot_save_failed})
     */
    List<Long> complete(CompleteSearchExecution command);

    /** {@code search_execution.execution_type}. baseline COLUMN COMMENT 가 어휘를 닫아 두었다. */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ExecutionType {
        /** 사용자가 직접 한 검색. */
        ORIGINAL("original"),
        /** 원 검색의 조건을 재사용한 자동 재검색. {@code replay_of_feedback_id} 와 짝이 맞아야 한다 (ck 제약). */
        REPLAY("replay");

        private final String storedValue;
    }

    /**
     * {@code search_execution.parse_source}.
     *
     * <p>{@link #RESOLVER_RULE} 은 <b>규칙을 1개 이상 성공 적용</b>했을 때만이다. 조건 일치나 건너뜀만으로는 아니다 (baseline COLUMN COMMENT).
     * 이 값과 {@code applied_rules_json} 에 {@code applied} 가 있는지는 항상 일치한다 — 조립이 둘을 같은 판정에서 만든다.
     *
     * <p>응답의 {@code query_resolution_status} 로 접을 때 {@link #RESOLVER} 와 {@link #RESOLVER_RULE} 은 <b>둘 다</b>
     * {@code resolved} 다. {@link #FALLBACK} 만 {@code fallback} 이다.
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ParseSource {
        RESOLVER("resolver"),
        RESOLVER_RULE("resolver_rule"),
        FALLBACK("fallback");

        private final String storedValue;

        public boolean resolved() {
            return this != FALLBACK;
        }
    }

    /**
     * {@code search_execution.status}.
     *
     * <p>{@link #RUNNING} 으로 열리고 {@link #complete} 가 나머지 셋 중 하나로 닫는다. {@code complete} 가 영영 오지 않은 실행은
     * {@code RUNNING} 으로 남는다 — 성공 기록으로 읽지 않는다 (§6.2).
     */
    @Getter
    @Accessors(fluent = true)
    @RequiredArgsConstructor
    enum ExecutionStatus {
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        DEGRADED("degraded"),
        FAILED("failed");

        private final String storedValue;
    }
}
