package com.npick.search.application.port;

/**
 * 실행 기록 저장이 실패했다.
 *
 * <p>구현(S15P21A501-60)은 저장 실패를 이 하나로 던진다. 세분화가 필요하면 원인을 {@code cause} 로 감싼다 — 조립이 갈라 보는 것은 <b>어느 지점에서</b> 실패했는가뿐이고, 그
 * 판단에는 예외 종류가 필요 없다.
 *
 * <p>{@code start} 에서 나오면 검색 실패, {@code complete} 에서 나오면 미저장 결과 제공이다 (FRD §6.2). 같은 예외를 두 지점이 다르게 다루는 것은 §6.2 가 그 둘을 다른
 * 실패로 적어 두었기 때문이다 — 전자는 아직 사용자에게 줄 것이 없고, 후자는 이미 계산이 끝났다.
 */
public class SearchRecordingException extends RuntimeException {

    public SearchRecordingException(String message) {
        super(message);
    }

    public SearchRecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
