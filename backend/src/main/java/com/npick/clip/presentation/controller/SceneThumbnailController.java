package com.npick.clip.presentation.controller;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
 * <p><b>축소하지 않는다.</b> 응답은 원본 해상도 keyframe 이다. 서버는 resize·crop·재인코딩하지 않고, FE 가 원본 크기를 전제로 지연 로딩·동시 요청 수와 화면 표시 크기를 조절한다.
 * 계약 정본은 {@code docs/contracts/web-api.md} §6.7 이다.
 *
 * <p>원본 영상 재생({@code /api/v1/media/{clipId}}, S15P21A501-133)과 책임이 다르다. 저 쪽은 큰 파일을 Range 로 흘려보내며 중간 캐시에 남기지 않고, 이 쪽은 한
 * 장을 통째로 주며 브라우저가 캐시하되 쓸 때마다 서버에 되묻게 한다.
 */
@RestController
public class SceneThumbnailController {

    /**
     * 브라우저가 바이트는 보관하되 쓰기 전에 반드시 서버에 되묻는다.
     *
     * <p>{@code max-age} 를 주면 그 시간 동안 요청 자체가 오지 않는다. 그러면 로그아웃·계정 전환 뒤에도, 클립을 논리 삭제한 뒤에도 Spring Security 와
     * {@code deleted_at} 조회를 거치지 않고 옛 프레임이 화면에 남는다 — 「삭제된 클립의 장면은 없는 장면과 같은 응답을 준다」 는 이 endpoint 의 계약과 어긋난다.
     * {@code no-cache} 는 보관을 막지 않고 무조건 재검증만 시키므로, 권한·삭제 판정은 매번 다시 내려지고 바뀌지 않은 이미지는 304 로 끝나 바이트가 오가지 않는다.
     *
     * <p>{@code private} 인 이유는 프레임이 공유 캐시나 프록시에 남으면 안 되기 때문이다 (FRD §6.4).
     */
    private static final String CACHE_CONTROL =
            CacheControl.noCache().cachePrivate().getHeaderValue();

    private final GetSceneThumbnailUseCase thumbnail;

    public SceneThumbnailController(GetSceneThumbnailUseCase thumbnail) {
        this.thumbnail = thumbnail;
    }

    @GetMapping("/api/v1/scenes/{sceneId}/thumbnail")
    @Operation(
            summary = "장면 대표 이미지 조회",
            description =
                    "로그인한 EDITOR·REVIEWER 가 조회한다. AI 가 선명도로 골라 첫 번째로 저장한 대표 keyframe(최소 keyframe_id)을 준다. 성공 본문은 이미지 바이트라 공통 Envelope 를 쓰지 않는다. Cache-Control 은 private, no-cache 이고 ETag 가 맞으면 304 다. 장면 없음 SCENE_404_001, 대표 이미지 없음 SCENE_404_002, 파일 누락 SCENE_404_003 을 구분한다.")
    public ResponseEntity<byte[]> thumbnail(
            @PathVariable long sceneId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        SceneThumbnailResult image = thumbnail.get(sceneId);
        String entityTag = "\"" + image.entityTag() + "\"";

        // 권한·삭제 판정은 이미 위에서 끝났다. 여기서 아끼는 것은 바이트 전송뿐이다.
        if (matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .eTag(entityTag)
                    .build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .eTag(entityTag)
                .body(image.bytes());
    }

    /** {@code If-None-Match} 는 값을 여럿 실을 수 있고 {@code W/} 접두사가 붙을 수 있다 (RFC 9110 §13.1.2). */
    private static boolean matches(String ifNoneMatch, String entityTag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (trimmed.startsWith("W/")) {
                trimmed = trimmed.substring(2);
            }
            if (entityTag.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }
}
