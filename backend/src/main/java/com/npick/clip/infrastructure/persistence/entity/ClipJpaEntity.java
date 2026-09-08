package com.npick.clip.infrastructure.persistence.entity;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "clip", schema = "npick")
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClipJpaEntity {
    @Id
    @Column(name = "clip_id", nullable = false)
    private Long clipId;

    @Column(name = "source_type", nullable = false, length = 32)
    private String sourceType;

    @Column(name = "storage_key", nullable = false, columnDefinition = "text")
    private String storageKey;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "transcript_file_key", columnDefinition = "text")
    private String transcriptFileKey;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "transcript_source", nullable = false, length = 32)
    private String transcriptSource;

    @Column(name = "script_text", columnDefinition = "text")
    private String scriptText;

    @Column(name = "registered_by_id", nullable = false)
    private Long registeredById;

    @Column(name = "active_pipeline_run_id")
    private Long activePipelineRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
