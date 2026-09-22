package com.npick.search.infrastructure.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * npick.search.candidate — 단어 검색 실행 설정 (FRD §11 "실행 환경 설정: 개발하면서 실측으로 정함").
 *
 * <p>여기의 기본값은 <b>확정값이 아니다.</b> 순위 가중치와 후보 개수를 문서나 코드에서 임의 숫자로 확정하지 않기로 정해져 있어(§11), 실측 전까지는 전 필드를 1.0 으로 두고 실제 색인에서 측정한
 * 뒤 조정한다. 숫자를 바꿀 때는 {@code config-version} 도 함께 올려야 한다 — 그러지 않으면 다른 설정으로 돌린 두 실행이 {@code search_execution} 에서 같은 설정으로
 * 보인다 (F-05 완료 기준).
 *
 * <p>가중치 0 은 "그 필드를 검색 대상에서 뺀다" 는 뜻이다. 필드 목록을 따로 두지 않는 이유는 대상 필드가 곧 BM25 인덱스의 칸 구성이라 바꾸려면 마이그레이션이 함께 필요하고, 설정만으로 늘릴 수
 * 있는 값이 아니기 때문이다.
 *
 * <p>{@code clip.script_text}(시각 정보 없는 일반 대본)는 어떤 BM25 인덱스에도 들어 있지 않아 여기서 켤 수 없다. 기본 비포함 요구(F-05, 이슈 제약)는 색인 구성으로 이미
 * 지켜진다.
 *
 * @param configVersion {@code search_execution.config_version} 에 그대로 기록할 값
 * @param captionWeight {@code scene.caption_tokens} 가중치. 0 이면 장면 설명을 검색하지 않는다
 * @param transcriptWeight {@code scene.transcript_tokens} 가중치. 0 이면 대사를 검색하지 않는다
 * @param ocrWeight {@code ocr_observation.tokens} 가중치. 0 이면 화면 글자를 검색하지 않는다
 * @param expandedTermMaxDf 확장어 토큰의 문서빈도 상한 (S15P21A501-302). 활성 장면 중 이 비율을 <b>넘는</b> 확장어 토큰은 후보 조회에서 버린다.
 *     {@code 장면}·{@code 하}·{@code 사람} 같은 상위어·기능어가 확장어로 들어와 변별력 없이 후보를 끌어오는 것을 막는다. 원 질의 토큰에는 걸지 않는다 — 사용자가 직접 친 말은 흔하다는
 *     이유로 버리지 않는다. 1.0 이면 아무것도 버리지 않는다
 * @param poolSize 다음 단계로 넘길 후보 상한. 최종 반환 10개(F-05 6항)가 아니라 재순위·제외 전의 pool 크기다. {@code @Max} 는 실측으로 정한 운영값이 아니라 <b>설정
 *     오타를 잡는 선</b>이다 — 0 을 하나 더 찍으면 한 요청이 수십만 행을 메모리로 올리는데, 부팅도 되고 검색도 되어 아무 신호가 없다. 이보다 큰 pool 이 필요해지면 왜 필요한지를 함께 적고 이
 *     값을 올린다
 */
@Validated
@ConfigurationProperties("npick.search.candidate")
public record SceneCandidateProperties(
        @NotBlank @Size(max = 128) String configVersion,
        @NotNull @PositiveOrZero Double captionWeight,
        @NotNull @PositiveOrZero Double transcriptWeight,
        @NotNull @PositiveOrZero Double ocrWeight,
        @NotNull @PositiveOrZero Double expandedWeight,
        @NotNull Double expandedTermMaxDf,
        @NotNull @Positive @Max(10_000) Integer poolSize) {

    /**
     * 0 이하면 확장어가 전부 잘려 동의어 검색(F-04 가 여기로 위임한 유일한 경로)이 통째로 죽고, 1 을 넘으면 어떤 문서빈도도 넘지 못해 설정이 아무 일도 하지 않는다. 둘 다 조용히 지나가는
     * 오설정이라 부팅에서 막는다 — dense 의 {@code max-distance} 와 같은 이유다 (S15P21A501-278).
     */
    @AssertTrue(message = "npick.search.candidate.expanded-term-max-df 는 0 초과 1 이하여야 한다 (활성 장면 대비 비율)")
    public boolean isExpandedTermMaxDfInRange() {
        // NaN 은 어느 비교에도 걸리지 않으므로 부정으로 판정한다.
        return expandedTermMaxDf != null && expandedTermMaxDf > 0 && expandedTermMaxDf <= 1.0;
    }

    /** 전 필드가 0 이면 어떤 질의든 결과가 0건이 된다. 검색 실패를 결과 0건으로 위장하는 상태(F-06 완료 기준)라 부팅 단계에서 막는다. */
    @AssertTrue(message = "단어 검색 대상 필드가 하나도 없다. caption/transcript/ocr 가중치 중 하나는 0보다 커야 한다") public boolean isAnyFieldSearched() {
        return captionWeight != null
                && transcriptWeight != null
                && ocrWeight != null
                && (captionWeight > 0 || transcriptWeight > 0 || ocrWeight > 0);
    }
}
