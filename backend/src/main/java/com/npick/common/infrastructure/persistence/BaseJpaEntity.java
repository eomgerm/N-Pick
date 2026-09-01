package com.npick.common.infrastructure.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.LastModifiedDate;

@MappedSuperclass
public abstract class BaseJpaEntity extends BaseCreatedJpaEntity {

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(6)")
    private Instant updatedAt;

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
