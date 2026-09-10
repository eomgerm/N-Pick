package com.npick.tag.domain.model;

import java.time.Instant;
import java.util.Set;

/**
 * 판정기에 먹이는 근거 한 줄. {@code tag_evidence} 행 하나가 이미 상속까지 펼쳐진 상태다 (FRD v3.1 F-04·F-10).
 *
 * <p>클립 전체 태그({@code tagging.scene_id IS NULL})는 그 클립의 장면마다 한 줄씩 들어온다. 그래서 판정기는 상속을 다시 계산하지 않고 {@code (sceneId, tagId)}
 * 로 그룹만 짓는다.
 *
 * <p>{@code source}·{@code verificationStatus} 를 enum 이 아니라 문자열로 받는 이유: 판정에 필요한 구분은 아래 네 개 술어뿐이고, 이 컬럼들은
 * {@code ck_evidence_review_shape} 가 이미 두 갈래로 제한한다. 값 어휘를 여기서 한 번 더 정의하면 정본이 두 곳이 된다.
 *
 * @param sceneId 이 근거가 적용되는 장면. 클립 태그도 장면별로 펼쳐져 들어온다
 * @param clipId 그 장면이 속한 클립
 * @param tagId 어느 태그인가. 같은 장면·태그의 근거가 한 그룹이 된다
 * @param sceneScoped 이 근거가 붙은 {@code tagging} 이 장면 태그인가. {@code false} 면 클립 태그의 상속이다
 * @param source {@code tag_evidence.source}
 * @param verificationStatus {@code tag_evidence.verification_status}
 * @param createdAt 판단 시각. 최신 판단을 고르는 1차 기준
 * @param evidenceId 시각 동률을 가르는 2차 기준 (컬럼 주석이 정한 방식)
 */
public record TagJudgment(
        long sceneId,
        long clipId,
        long tagId,
        TagType tagType,
        String matchValue,
        String name,
        boolean sceneScoped,
        String source,
        String verificationStatus,
        Instant createdAt,
        long evidenceId) {

    private static final String REVIEWER_FEEDBACK = "reviewer_feedback";
    private static final String VERIFIED = "verified";
    private static final String WITHDRAWN = "withdrawn";

    /**
     * 관측 근거의 출처. 여기 없는 {@code asr}·{@code vlm}·{@code rule} 은 추정 근거다.
     *
     * <p>FRD F-04 — "사용자 입력·원본 정보·CC·OCR 등 관측 근거와 ASR·VLM·규칙의 추정 근거를 구분한다. AI 의 높은 신뢰도만으로 검증된 사실로 올리지 않는다."
     */
    private static final Set<String> OBSERVATION_SOURCES = Set.of("user_input", "original_metadata", "cc", "ocr");

    /** 그룹 키. 판정은 장면 하나와 태그 하나를 놓고 한다. */
    public record Key(long sceneId, long tagId) {}

    public Key key() {
        return new Key(sceneId, tagId);
    }

    /** 사람의 검수 판단인가. 아니면 추출 근거다. */
    public boolean reviewer() {
        return REVIEWER_FEEDBACK.equals(source);
    }

    /**
     * 개입 해제인가.
     *
     * <p>해제는 "그 범위에 사람 판단이 없는 것처럼 원본·상속을 다시 평가" 하라는 뜻이다. 과거 승인을 되살리는 동작이 아니다 (F-10).
     */
    public boolean withdrawn() {
        return WITHDRAWN.equals(verificationStatus);
    }

    /** 사람이 승인한 판단인가. */
    public boolean approvedByReviewer() {
        return reviewer() && VERIFIED.equals(verificationStatus);
    }

    /**
     * 검증된 관측 근거인가.
     *
     * <p>추정 근거({@code asr}·{@code vlm}·{@code rule})는 {@code verification_status} 가 {@code verified} 로 저장돼 있어도 여기서
     * {@code false} 다. {@code ck_evidence_review_shape} 가 추출 근거에 {@code verified} 를 허용하므로 그 값이 실제로 들어올 수 있고, 그대로 통과시키면
     * F-06 이 AI 추측 날짜를 근거로 장면을 <b>제외</b> 해 버린다.
     */
    public boolean observationVerified() {
        return OBSERVATION_SOURCES.contains(source) && VERIFIED.equals(verificationStatus);
    }
}
