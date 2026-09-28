package com.npick.search.application.port;

import java.util.List;

/**
 * 검색 본 트랜잭션과 독립적으로 실행 스냅샷을 시작하고 완결한다.
 *
 * <p>호출 규약: 한 검색당 {@link #start} 1회, 그 뒤 {@link #recordResolution} 최대 1회, 마지막으로 {@link #complete} 또는 {@link #fail} 중
 * <b>정확히 하나</b>를 1회. {@code start} 가 던지면 나머지는 부르지 않는다. 열어 놓고 닫지 않으면 그 행은 영구히 {@code running} 으로 남아 성공 기록과 구분되지 않는다.
 *
 * <p>모든 호출은 검색 조회 트랜잭션 <b>밖</b>에서 각각 커밋되어야 한다. 후보 검증(F-12)은 임시 반영→검색→ROLLBACK 으로 도는데, 기록이 같은 트랜잭션에 묶이면 롤백과 함께 사라진다
 * (baseline 주석).
 */
public interface SearchExecutionRecordPort {
    /**
     * 원문과 실행자만으로 {@code running} 실행을 연다.
     *
     * @return {@code search_execution_id}
     * @throws SearchRecordingException 저장 실패. 조립은 §6.2 「검색 실행의 최초 저장 실패」로 AI 호출 전에 검색을 중단한다
     */
    long start(StartSearchExecution command);

    /**
     * 리졸버 응답을 받은 직후 {@code running} 실행에 해석 스냅샷을 덧붙인다.
     *
     * @throws SearchRecordingException 저장 실패
     */
    void recordResolution(RecordSearchExecutionResolution command);

    /**
     * 결과를 확정해 닫는다.
     *
     * @return {@code rankedScenes} 와 같은 순서·같은 길이의 {@code search_result_id}
     * @throws SearchRecordingException 저장 실패. 조립은 계산된 결과를 미저장 상태로 내보낸다 (§6.2 · web-api §5.1
     *     {@code snapshot_save_failed})
     */
    List<Long> complete(CompleteSearchExecution command);

    /**
     * 결과를 내지 못하고 끝난 실행을 {@code failed} 로 닫는다.
     *
     * <p>{@link #complete} 와 따로 둔 이유는 실패 시점에 넘길 것이 없기 때문이다. 설정 스냅샷과 후보는 순위 계산이 끝나야 나오는데, 리졸버 타임아웃·활성 규칙 조회 실패·후보 조회 실패는
     * 그 전에 끊긴다. {@code complete} 의 계약이 요구하는 값을 만들어 낼 수 없으므로 빈 값으로 지어내는 대신 별도 경로로 닫는다.
     *
     * <p><b>이 호출은 예외를 밖으로 내보내지 않는다.</b> 사용자에게 돌아가야 하는 것은 검색이 왜 실패했는가이지, 그 실패를 기록하다 또 실패했다는 사실이 아니다. 기록 실패는 비밀정보 없는 운영
     * 로그로만 남긴다 (§6.2 「성공 기록이 남았다고 주장하지 않고 사용자 안내와 비밀정보 없는 운영 로그로 구분한다」). 조립은 원래 검색 실패 사유를 그대로 올리면 된다.
     *
     * @param errorCode {@code search_execution.error_code} 에 남길 공개 오류 코드. {@code SearchExecutionErrorCode} 의
     *     {@code SRCH_503_0xx} 또는 그 실행을 끊은 다른 검색 코드의 값이다. 비어 있으면 기록하지 않고 로그만 남긴다
     * @param executionMs 실패로 끝나기까지 앱이 실제로 잰 시간. 음수면 기록하지 않고 로그만 남긴다
     */
    void fail(long searchExecutionId, String errorCode, int executionMs);
}
