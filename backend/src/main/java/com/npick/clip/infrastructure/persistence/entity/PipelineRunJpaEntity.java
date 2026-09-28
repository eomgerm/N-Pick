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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pipeline_run", schema = "npick")
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PipelineRunJpaEntity {
    @Id
    @Column(name = "pipeline_run_id", nullable = false)
    private Long pipelineRunId;

    @Column(name = "clip_id", nullable = false)
    private Long clipId;

    @Column(name = "processing_no", nullable = false)
    private Integer processingNo;

    @Column(name = "pipeline_version", nullable = false, length = 128)
    private String pipelineVersion;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_states_json", nullable = false, columnDefinition = "jsonb")
    private String stageStatesJson;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
