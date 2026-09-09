package com.npick.clip.application.port;
/** 현재 로그인 사용자의 reviewer 권한을 확인한 뒤 회원 ID를 반환한다. */
public interface RegistrationActorPort {
    long requireReviewerId();
}
