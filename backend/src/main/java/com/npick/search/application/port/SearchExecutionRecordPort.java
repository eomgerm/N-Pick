package com.npick.search.application.port;

import java.util.List;

/** 검색 본 트랜잭션과 독립적으로 실행 스냅샷을 시작하고 완결한다. */
public interface SearchExecutionRecordPort {
    long start(StartSearchExecution command);

    List<Long> complete(CompleteSearchExecution command);
}
