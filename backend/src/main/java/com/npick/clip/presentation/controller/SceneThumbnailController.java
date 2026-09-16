package com.npick.clip.presentation.controller;

import java.time.Duration;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.npick.clip.application.query.thumbnail.GetSceneThumbnailUseCase;
import com.npick.clip.application.query.thumbnail.SceneThumbnailResult;

/**
 * 결과 카드·문의 큐의 장면 대표 이미지 (FRD F-03·F-07).
 *
 * <p>경로가 아니라 scene_id 로만 접근한다. {@code keyframe.storage_key} 와 서버 절대 경로는 응답 어디에도 나가지 않는다 (FRD §6.4). 응답 본문이 이미지 바이트이므로
 * 성공 응답에는 공통 Envelope 를 쓰지 않는다(설계 정본 §12). 실패는 공통 Envelope 로 내려간다.
 *
 * <p>검색·문의 응답은 이미지를 싣지 않고 {@code scene_id} 만 준다. 브라우저가 그 값으로 이 주소를 조립해 따로 요청한다 — 계약 정본
 * {@code docs/contracts/web-api.md} §5.1 이 정한 규칙이다. JSON 이 Base64 를 실으면 결과 10건이 한 응답에 수 MB 를 얹고 그 바이트가 캐시되지 못한다.
 *
 * <p>형식은 어댑터가 파일 머리글로 정하고, 브라우저가 본문을 다시 추측하지 않도록 {@code X-Content-Type-Options: nosniff} 가 함께 나간다 (FRD §6.4). 이 헤더는 보안
 * 필터 체인이 응답마다 붙이므로 여기서 다시 붙이지 않는다 — 두 곳이 같은 헤더를 쓰면 어느 쪽이 정본인지 알 수 없다. 대신 {@code SceneThumbnailControllerTest} 가 이 응답에
 * 실제로 붙는지 고정한다.
 *
 * <p>원본 영상 재생({@code /api/v1/media/{clipId}}, S15P21A501-133)과 책임이 다르다. 저 쪽은 큰 파일을 Range 로 흘려보내며 중간 캐시에 남기지 않고, 이 쪽은 작은
 * 이미지를 통째로 주며 브라우저가 캐시하기를 바란다.
 */
@RestController
public class SceneThumbnailController {

    /**
     * scene_id 하나가 가리키는 대표 이미지는 바뀌지 않는다. 재처리는 새 {@code pipeline_run} 과 새 {@code scene_id} 를 만들지 기존 장면의 keyframe 을 바꾸지
     * 않는다. 그래서 재검증 없이 그대로 쓰게 두어도 안전하다.
     *
     * <p>{@code private} 인 이유는 프레임이 공유 캐시나 프록시에 남으면 안 되기 때문이다 (FRD §6.4). 브라우저 한 대의 캐시까지 막으면 결과 카드 10장이 화면을 오갈 때마다 다시
     * 내려받게 되어 이 API 를 만든 이유가 없어진다.
     */
    private static final String CACHE_CONTROL =
            CacheControl.maxAge(Duration.ofDays(1)).cachePrivate().immutable().getHeaderValue();

    private final GetSceneThumbnailUseCase thumbnail;

    public SceneThumbnailController(GetSceneThumbnailUseCase thumbnail) {
        this.thumbnail = thumbnail;
    }

    @GetMapping("/api/v1/scenes/{sceneId}/thumbnail")
    @Operation(
            summary = "장면 대표 이미지 조회",
            description =
                    "로그인한 EDITOR·REVIEWER 가 조회한다. AI 가 선명도로 골라 첫 번째로 저장한 대표 keyframe(최소 keyframe_id)을 준다. 성공 본문은 이미지 바이트라 공통 Envelope 를 쓰지 않는다. 장면 없음 SCENE_404_001, 대표 이미지 없음 SCENE_404_002, 파일 누락 SCENE_404_003 을 구분한다.")
    public ResponseEntity<byte[]> thumbnail(@PathVariable long sceneId) {
        SceneThumbnailResult image = thumbnail.get(sceneId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(image.bytes());
    }
}
