package com.npick.search.infrastructure.persistence;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.port.SearchExecutionRecordPort;
import com.npick.search.application.port.SearchRecordingException;
import com.npick.search.application.port.StartSearchExecution;

/**
 * 실행 기록을 저장하지 않는 임시 구현. <b>S15P21A501-60 이 실제 어댑터를 넣으면 이 파일을 지운다.</b>
 *
 * <p>조립(-59)은 -60 의 저장 계약을 부르는 쪽만 소유한다. 그 구현이 오기 전까지 검색 자체는 돌아야 하므로, 저장만 실패시켜 계약이 정해 둔 미저장 경로로 흘린다 — 응답의
 * {@code search_execution_id}·{@code search_result_id} 가 전부 {@code null} 이고 {@code degraded_reasons} 에
 * {@code snapshot_save_failed} 가 붙는다 (web-api §5.1).
 *
 * <p><b>{@code start} 는 던지지 않는다.</b> §6.2 의 「최초 저장 실패 → 중단」은 실제 저장이 실패했을 때의 규칙이고, 아직 배선되지 않은 것은 장애가 아니다. 여기서 던지면 모든 검색이
 * 500 이 되어 나머지 단계를 확인할 수 없다.
 *
 * <p>여기서 돌려주는 값이 사용자에게 새 나가지는 않는다. 조립은 {@code complete} 가 성공했을 때만 실행 ID 를 응답에 싣는다 — 이 구현은 그 단계에서 던지므로 응답의 ID 는 항상
 * {@code null} 이다. §6.2 가 금지한 「저장된 결과 ID 를 만들었다고 표시」는 일어나지 않는다.
 *
 * <p>조건부 빈으로 만들지 않았다. -60 어댑터가 들어오면 같은 타입의 빈이 둘이라 기동이 실패하고, 그 오류가 두 클래스 이름을 다 찍어 준다 — 지워야 할 파일이 무엇인지 바로 보이는 쪽이 조건이 조용히
 * 이 구현을 살려 두는 것보다 낫다.
 */
@Repository
class UnwiredSearchExecutionRecordAdapter implements SearchExecutionRecordPort {

    private static final String NOT_WIRED = "검색 실행 기록 저장이 아직 배선되지 않았다 (S15P21A501-60)";

    /** 저장된 적 없는 실행. 응답까지 가지 않는다 — complete 가 던지므로 조립이 ID 를 null 로 내보낸다. */
    private static final long UNSAVED = 0L;

    @Override
    public long start(StartSearchExecution command) {
        return UNSAVED;
    }

    @Override
    public List<Long> complete(CompleteSearchExecution command) {
        throw new SearchRecordingException(NOT_WIRED);
    }

    @Override
    public void fail(long searchExecutionId, String errorCode, int executionMs) {
        // 남길 곳이 없다. 던지지 않는 이유는 계약이 그렇기도 하고, 여기서 던지면 조립이 삼켜야 할
        // 예외가 하나 더 느는 것뿐이기 때문이다.
    }
}
