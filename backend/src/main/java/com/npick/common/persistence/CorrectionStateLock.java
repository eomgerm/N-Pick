package com.npick.common.persistence;

import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

/**
 * 교정 상태(후보·활성 규칙 집합·확정 근거)를 바꾸는 경로들을 서로 직렬화하는 트랜잭션 스코프 advisory lock (S15P21A501-84·-220, F-13).
 *
 * <p>확정(지문 재확인→쓰기), 후보 생성(전제 재확인→쓰기), 신고 판정 변경, 규칙 사용 중단이 같은 교정 상태를 건드리므로 모두 이 잠금을 먼저 잡아야 "검증 이후 상태가 바뀌면 재검증"이 실제로
 * 보장된다. 잠금 없이 한쪽만 직렬화하면, 다른 경로가 전제나 지문을 읽은 뒤 상태를 바꿔 stale 한 후보를 저장하거나 stale 한 집합으로 확정할 수 있다.
 *
 * <p>여러 도메인(feedback 확정·search 규칙 중단)이 공유하는 기술 계약이라 {@code common} 에 둔다(설계 정본 §12). {@code pg_advisory_xact_lock} 은
 * {@code void} 라 {@code SELECT} 로 감싸 값을 받고, 잠금은 커밋/롤백에서 자동 해제된다.
 */
@Component
public class CorrectionStateLock {

    // 활성 규칙 집합·확정 근거를 바꾸는 모든 경로가 공유하는 단일 키. 값이 갈리면 직렬화가 깨지므로 한 곳에서만 정의한다.
    private static final long KEY = 8401L;

    private final EntityManager em;

    public CorrectionStateLock(EntityManager em) {
        this.em = em;
    }

    /** 현재 트랜잭션에서 교정 상태 잠금을 잡는다. 이미 다른 트랜잭션이 잡고 있으면 그 트랜잭션이 끝날 때까지 대기한다. */
    public void acquire() {
        em.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) locked")
                .setParameter("key", KEY)
                .getSingleResult();
    }
}
