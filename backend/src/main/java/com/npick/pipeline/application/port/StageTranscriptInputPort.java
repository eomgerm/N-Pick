package com.npick.pipeline.application.port;

import java.util.List;
import java.util.Map;

/**
 * {@code transcript_selection} 배정 직전에 {@code clip} 이 자막 입력을 준비한다.
 *
 * <p>방향을 뒤집어 고리를 끊는 이유는 {@link ClipMediaInputPort} 와 같다. 준비 결과를 계약 §4.5 의 {@code inputs.upstream.transcript} 모양 그대로
 * 돌려받는다 — 준비한 쪽이 직렬화까지 마치면 {@code pipeline} 이 {@code clip} 의 준비 결과 타입(자막 구간·임베디드 트랙 검사 등)을 하나도 알 필요가 없다.
 */
public interface StageTranscriptInputPort {

    Prepared prepare(long clipId, long runId, String outputKeyPrefix);

    /** 보존하지 않고 닫으면 준비물이 정리된다. {@link #retain()} 을 부른 뒤 닫아야 남는다. */
    interface Prepared extends AutoCloseable {

        /** {@code inputs.upstream.transcript} 에 그대로 실을 값. */
        Map<String, Object> transcript();

        /** 배정에 기록할 입력 산출물 참조. */
        List<Map<String, Object>> artifacts();

        /** 배정 기록 성공·결과 불명확 시 보존. I/O 없는 멱등 전환. */
        void retain();

        @Override
        void close();
    }
}
