package com.npick.clip.application.query.media;

/** 다운로드할 영상 본문과 브라우저에 제시할 파일 정보. */
public record ClipMediaDownloadResult(
        String fileName, String contentType, long sizeBytes, String internalLocation, ClipMediaBody body)
        implements AutoCloseable {

    @Override
    public void close() {
        body.close();
    }
}
