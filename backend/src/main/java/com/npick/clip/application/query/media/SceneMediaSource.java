package com.npick.clip.application.query.media;

/** 장면 다운로드에 필요한 원본과 저장된 시간 경계. */
public record SceneMediaSource(String storageKey, long startTimeMs, long endTimeMs) {}
