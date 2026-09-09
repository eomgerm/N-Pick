package com.npick.clip.presentation.controller;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.npick.clip.application.query.media.ClipMediaStreamResult;
import com.npick.clip.application.query.media.StreamClipMediaQuery;
import com.npick.clip.application.query.media.StreamClipMediaUseCase;
import com.npick.common.error.BusinessException;

/**
 * Preview 재생용 영상 스트리밍 (FR-RES-013~015).
 *
 * <p>경로가 아니라 clip_id 로만 접근한다. 응답 본문이 영상 바이트이므로 성공 응답에는 공통 Envelope 를 쓰지 않는다(설계 정본 §12). 실패는 공통 Envelope 로 내려간다.
 *
 * <p>바이트 전송은 두 경로가 있다. 프록시 뒤에서는 {@code X-Accel-Redirect} 로 위임하고(03-deployment.md §31), 프록시가 없는 로컬에서는 애플리케이션이 직접 쓴다. 어느
 * 쪽이든 clip_id 해석·경로 이탈 차단·Range 검증은 API 가 한다.
 */
@RestController
public class ClipMediaController {

    private static final Logger log = LoggerFactory.getLogger(ClipMediaController.class);
    private static final String ACCEL_REDIRECT_HEADER = "X-Accel-Redirect";

    private final StreamClipMediaUseCase stream;

    public ClipMediaController(StreamClipMediaUseCase stream) {
        this.stream = stream;
    }

    @GetMapping("/api/v1/media/{clipId}")
    public void play(
            @PathVariable long clipId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
            HttpServletResponse response)
            throws IOException {
        long startedAt = System.nanoTime();
        ClipMediaStreamResult media = stream.stream(new StreamClipMediaQuery(clipId, range));

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(media.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        // 원본 영상을 중간 캐시나 브라우저 디스크에 남기지 않는다 (FRD §6.4).
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");

        if (media.internalLocation() != null) {
            // 프록시가 원본 요청의 Range 를 다시 읽어 206 을 만든다. 길이 헤더를 우리가 붙이면 어긋난다.
            response.setHeader(ACCEL_REDIRECT_HEADER, media.internalLocation());
            logServerShare(startedAt, clipId, media, "accel");
            return;
        }

        if (media.partial()) {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(
                    HttpHeaders.CONTENT_RANGE,
                    "bytes %d-%d/%d"
                            .formatted(media.offset(), media.offset() + media.length() - 1, media.totalBytes()));
        }
        response.setContentLengthLong(media.length());
        logServerShare(startedAt, clipId, media, "direct");
        try {
            media.body().writeTo(response.getOutputStream());
        } catch (BusinessException failure) {
            if (!response.isCommitted()) {
                throw failure;
            }
            // 응답이 시작된 뒤에는 상태 코드를 바꿀 수 없다. seek 로 인한 클라이언트 중단이 대부분이다.
            log.debug("Preview 전송이 중단되었습니다. clipId={}", clipId, failure);
        }
    }

    /**
     * 첫 프레임 p95 2초 예산의 서버 몫 (NFR-PERF-002).
     *
     * <p>요청 진입부터 본문 첫 바이트 직전까지, 즉 clip 조회·경로 해석·Range 검증에 걸린 시간이다. 전송 시간과 클라이언트 디코딩은 포함하지 않는다. local 프로파일이
     * {@code com.npick} 을 DEBUG 로 남기므로 측정은 그 로그로 한다.
     */
    private static void logServerShare(long startedAt, long clipId, ClipMediaStreamResult media, String delivery) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "preview first-byte clipId={} delivery={} partial={} offset={} length={} total={} elapsedMs={}",
                clipId,
                delivery,
                media.partial(),
                media.offset(),
                media.length(),
                media.totalBytes(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }
}
