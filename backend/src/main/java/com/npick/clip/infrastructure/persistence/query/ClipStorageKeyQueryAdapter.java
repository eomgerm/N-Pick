package com.npick.clip.infrastructure.persistence.query;

import java.util.Optional;
import jakarta.persistence.EntityManager;

import org.springframework.stereotype.Component;

import com.npick.clip.application.query.media.ClipStorageKeyQueryPort;

/** 재생에 필요한 한 칸만 읽는다. Aggregate 를 적재하지 않으므로 Domain Repository 가 아니다(설계 정본 §9). */
@Component
public class ClipStorageKeyQueryAdapter implements ClipStorageKeyQueryPort {

    private final EntityManager entityManager;

    public ClipStorageKeyQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<String> findPlayableStorageKey(long clipId) {
        // 삭제 표시된 영상은 재생 대상이 아니다. 없는 영상과 같은 응답을 준다.
        return entityManager
                .createQuery(
                        "select c.storageKey from ClipJpaEntity c where c.clipId = :clipId and c.deletedAt is null",
                        String.class)
                .setParameter("clipId", clipId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }
}
