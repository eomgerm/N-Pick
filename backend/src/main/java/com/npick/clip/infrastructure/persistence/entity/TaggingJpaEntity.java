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
@Table(name = "tagging", schema = "npick")
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaggingJpaEntity {
    @Id
    @Column(name = "tagging_id", nullable = false)
    private Long taggingId;

    @Column(name = "clip_id", nullable = false)
    private Long clipId;

    @Column(name = "scene_id")
    private Long sceneId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
