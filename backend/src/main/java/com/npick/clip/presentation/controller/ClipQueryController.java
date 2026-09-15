package com.npick.clip.presentation.controller;

import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npick.clip.application.query.detail.GetClipUseCase;
import com.npick.clip.application.query.list.GetClipsUseCase;
import com.npick.clip.presentation.response.ClipDetailResponse;
import com.npick.clip.presentation.response.ClipPageResponse;
import com.npick.common.response.ApiResponse;

@RestController
public class ClipQueryController {
    private final GetClipsUseCase list;
    private final GetClipUseCase detail;

    public ClipQueryController(GetClipsUseCase list, GetClipUseCase detail) {
        this.list = list;
        this.detail = detail;
    }

    @GetMapping("/api/v1/clips")
    @io.swagger.v3.oas.annotations.Operation(
            summary = "클립 처리 목록 조회",
            description =
                    "검수자 전용. 논리 삭제 제외, page 0부터/size 1~100. search_available은 활성 검색 결과, latest_run은 최신 시도이며 검수 완료와 무관하다.")
    public ApiResponse<ClipPageResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @io.swagger.v3.oas.annotations.Parameter(
                            description =
                                    "최신 run 상태 OR 필터. 쉼표 또는 반복 파라미터로 queued,running,failed,succeeded,no_run 선택. 생략하면 전체. 처리 중은 queued,running, 완료는 succeeded이며 검수 완료와 무관하다.")
                    @RequestParam(required = false)
                    java.util.List<String> status) {
        return ApiResponse.success(
                ClipPageResponse.from(list.getClips(page, size, status == null ? java.util.List.of() : status)));
    }

    @GetMapping("/api/v1/clips/{id}")
    @io.swagger.v3.oas.annotations.Operation(
            summary = "클립 처리 상세 조회",
            description =
                    "기본 대사 출처는 활성 run 기준. processing_details는 latest_run.pipeline_run_id 기준으로 저장된 단계/채택 기록을 조회한다. 구버전·누락 기록은 성공으로 추정하지 않는다.")
    public ApiResponse<ClipDetailResponse> detail(@PathVariable @Positive(message = "클립 ID는 양수여야 합니다.") long id) {
        return ApiResponse.success(ClipDetailResponse.from(detail.getClip(id)));
    }
}
