package com.npick.common.infrastructure.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
public abstract class BaseSoftDeletableJpaEntity extends BaseJpaEntity {

    @Column(name = "deleted_at", columnDefinition = "TIMESTAMPTZ(6)")
    private Instant deletedAt;

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
