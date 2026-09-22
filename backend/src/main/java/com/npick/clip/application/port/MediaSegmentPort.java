package com.npick.clip.application.port;

/** 저장된 원본에서 시간 구간을 잘라 다운로드 가능한 영상으로 만드는 계약. */
public interface MediaSegmentPort {

    /**
     * @param storageKey media root 기준 원본 key
     * @param startTimeMs 포함하는 시작 시각
     * @param endTimeMs 포함하지 않는 종료 시각
     */
    MediaAssetPort.MediaAsset extract(String storageKey, long startTimeMs, long endTimeMs);
}
