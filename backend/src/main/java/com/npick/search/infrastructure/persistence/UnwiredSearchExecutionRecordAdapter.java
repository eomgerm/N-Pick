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
 * <p><b>{@code start} 도 던진다.</b> 여기서 가짜 ID 를 만들어 주면 FRD §6.2 가 금지한 「저장된 결과 ID 를 만들었다고 표시」가 된다. 그 ID 로 신고를 걸면
 * 존재하지 않는 실행을 가리키는 행이 생긴다.
 *
 * <p>조건부 빈으로 만들지 않았다. -60 어댑터가 들어오면 같은 타입의 빈이 둘이라 기동이 실패하고, 그 오류가 두 클래스 이름을 다 찍어 준다 — 지워야 할 파일이 무엇인지 바로 보이는 쪽이
 * 조건이 조용히 이 구현을 살려 두는 것보다 낫다.
 */
@Repository
class UnwiredSearchExecutionRecordAdapter implements SearchExecutionRecordPort {

    private static final String NOT_WIRED = "검색 실행 기록 저장이 아직 배선되지 않았다 (S15P21A501-60)";

    @Override
    public long start(StartSearchExecution command) {
        throw new SearchRecordingException(NOT_WIRED);
    }

    @Override
    public List<Long> complete(CompleteSearchExecution command) {
        throw new SearchRecordingException(NOT_WIRED);
    }
}
