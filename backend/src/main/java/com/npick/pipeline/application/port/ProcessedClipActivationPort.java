package com.npick.pipeline.application.port;

/**
 * {@code indexing} 이 성공한 run 을 클립의 활성 run 으로 전환한다.
 *
 * <p>게시 판정과 전환은 {@code clip} 의 책임이다. 방향을 뒤집어 고리를 끊는 이유는 {@link ClipMediaInputPort} 와 같다.
 */
public interface ProcessedClipActivationPort {

    /** @return 전환했으면 {@code true}. 게시 조건을 채우지 못하면 {@code false} 이며 run 은 실패가 아니다. */
    boolean activate(long clipId, long pipelineRunId, int processingNo);
}
