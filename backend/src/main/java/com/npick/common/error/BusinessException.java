package com.npick.common.error;

/**
 * 도메인·application 이 공유하는 유일한 예외 타입 (설계 정본 §13).
 *
 * <p>abstract 가 아니다 — 정본이 "도메인별 또는 오류별 RuntimeException 클래스를 반복해서 만들지 않고 공통 BusinessException 을 사용한다" 고 정했다. 계층별 차이는
 * {@link ErrorCode} 구현으로 표현한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
