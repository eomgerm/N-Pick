package com.npick.common.infrastructure.persistence;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseCreatedJpaEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "TIMESTAMPTZ(6)")
    private Instant createdAt;

    public Instant getCreatedAt() {
        return createdAt;
    }
}
