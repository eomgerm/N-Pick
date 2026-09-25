package com.npick.tag.application;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.tag.application.error.TagCorrectionCandidateErrorCode;
import com.npick.tag.application.port.TagContext;
import com.npick.tag.application.port.TagContextPort;
import com.npick.tag.domain.model.ReviewerTagJudgment;
import com.npick.tag.domain.repository.TagCorrectionCandidateRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateTagCorrectionCandidateServiceTest {

    private TagContextPort tagContextPort;
    private TagCorrectionCandidateRepository candidateRepository;
    private CorrectionStateLock correctionStateLock;
    private CreateTagCorrectionCandidateService service;

    @BeforeEach
    void setUp() {
        tagContextPort = mock(TagContextPort.class);
        candidateRepository = mock(TagCorrectionCandidateRepository.class);
        correctionStateLock = mock(CorrectionStateLock.class);
        service = new CreateTagCorrectionCandidateService(tagContextPort, candidateRepository, correctionStateLock);
    }

    private void reviewingTagCorrection() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "tag_correction", 9L, 300L, 100L)));
    }

    private CreateTagCorrectionCandidateCommand command(boolean reviewerRole, long reviewerId, TagOperation... ops) {
        return new CreateTagCorrectionCandidateCommand(1L, reviewerId, reviewerRole, List.of(ops));
    }

    @Test
    @DisplayName("교체(반려+추가)를 한 트랜잭션에서 장면 범위 판단 둘로 저장한다")
    void createsRejectAndApproveForReplace() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L, 5002L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true,
                9L,
                new TagOperation(TagCorrectionAction.REJECT, TagScope.SCENE, "location", "서울", "서울"),
                new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        verify(correctionStateLock).acquire();
        assertThat(result.evidenceIds()).containsExactly(5001L, 5002L);
        ArgumentCaptor<ReviewerTagJudgment> captor = ArgumentCaptor.forClass(ReviewerTagJudgment.class);
        verify(candidateRepository, org.mockito.Mockito.times(2)).addJudgment(captor.capture());
        ReviewerTagJudgment reject = captor.getAllValues().get(0);
        assertThat(reject.verificationStatus()).isEqualTo("rejected");
        assertThat(reject.matchValue()).isEqualTo("서울");
        assertThat(reject.sceneId()).isEqualTo(300L);
        assertThat(reject.clipId()).isEqualTo(100L);
        ReviewerTagJudgment approve = captor.getAllValues().get(1);
        assertThat(approve.verificationStatus()).isEqualTo("verified");
        assertThat(approve.matchValue()).isEqualTo("제주도");
    }

    @Test
    @DisplayName("클립 범위 판단은 sceneId 없이 저장한다")
    void clipScopeJudgmentHasNullScene() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.CLIP, "event", "포항지진", "포항 지진")));

        ArgumentCaptor<ReviewerTagJudgment> captor = ArgumentCaptor.forClass(ReviewerTagJudgment.class);
        verify(candidateRepository).addJudgment(captor.capture());
        assertThat(captor.getValue().sceneId()).isNull();
        assertThat(captor.getValue().clipId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("개입 해제는 withdrawn 으로 저장한다")
    void withdrawJudgment() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.WITHDRAW, TagScope.SCENE, "location", "서울", "서울")));

        ArgumentCaptor<ReviewerTagJudgment> captor = ArgumentCaptor.forClass(ReviewerTagJudgment.class);
        verify(candidateRepository).addJudgment(captor.capture());
        assertThat(captor.getValue().verificationStatus()).isEqualTo("withdrawn");
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.create(command(
                        false,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(candidateRepository, never()).addJudgment(any());
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(tagContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Test
    @DisplayName("검수 중이 아니면 거부한다")
    void rejectsNotReviewing() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("CLOSED", "tag_correction", 9L, 300L, 100L)));
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWING));
    }

    @Test
    @DisplayName("교정류가 아닌 종결 판정(오류없음 등)은 거부한다")
    void rejectsNotTagCorrection() {
        when(tagContextPort.find(1L)).thenReturn(Optional.of(new TagContext("REVIEWING", "no_action", 9L, 300L, 100L)));
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_TAG_CORRECTION));
    }

    @Test
    @DisplayName("patch_parse(태그·해석 모두 잘못) 신고에서도 태그 변경안을 만든다 (F-09, 레거시 하위 호환)")
    void acceptsPatchParseResolution() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "patch_parse", 9L, 300L, 100L)));
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(result.evidenceIds()).containsExactly(5001L);
    }

    @Test
    @DisplayName("통합 판정 correction 신고에서도 태그 변경안을 만든다 (S15P21A501-281)")
    void acceptsUnifiedCorrectionResolution() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "correction", 9L, 300L, 100L)));
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(result.evidenceIds()).containsExactly(5001L);
    }

    @Test
    @DisplayName("장면 제외로 처리된 신고에서도 태그 변경안을 만든다 (레거시 하위 호환, S15P21A501-281)")
    void acceptsExcludeSceneResolution() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "exclude_scene", 9L, 300L, 100L)));
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(result.evidenceIds()).containsExactly(5001L);
    }

    @Test
    @DisplayName("11종에 없는 tagType 은 400 으로 거부한다 (읽기측 500 위험 차단)")
    void rejectsUnknownTagType() {
        reviewingTagCorrection();
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "Location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.INVALID_TAG_TYPE));
    }

    @Test
    @DisplayName("matchValue 를 저장 전에 정규화한다 (쓰기/읽기 표기 일치)")
    void normalizesMatchValueBeforeSave() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);
        ArgumentCaptor<ReviewerTagJudgment> captor = ArgumentCaptor.forClass(ReviewerTagJudgment.class);

        service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "㈜한화", "㈜한화")));

        verify(candidateRepository).addJudgment(captor.capture());
        assertThat(captor.getValue().matchValue()).isEqualTo("(주)한화");
    }

    @Test
    @DisplayName("정규화하면 비는 matchValue 는 400 으로 거부한다")
    void rejectsBlankMatchValueAfterNormalize() {
        reviewingTagCorrection();
        // 공백류·불가시 문자는 정규화(TagMatchValue)에서 전부 제거된다 — 결과가 빈 값이면 저장 전에 400.
        String whitespaceOnly = "​  ";
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(
                                TagCorrectionAction.APPROVE, TagScope.SCENE, "location", whitespaceOnly, "x"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(TagCorrectionCandidateErrorCode.INVALID_MATCH_VALUE));
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingTagCorrection();
        assertThatThrownBy(() -> service.create(command(
                        true,
                        7L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_REVIEWER));
    }

    @Test
    @DisplayName("변경안이 비어 있으면 거부한다")
    void rejectsEmptyOperations() {
        reviewingTagCorrection();
        assertThatThrownBy(() -> service.create(command(true, 9L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.EMPTY_OPERATIONS));
        verify(candidateRepository, never()).addJudgment(any());
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("한 요청의 변경안이 상한(20)을 넘으면 하나도 저장하지 않고 거부한다")
    void rejectsTooManyOperationsInOneRequest() {
        reviewingTagCorrection();
        assertThatThrownBy(() -> service.create(command(true, 9L, operations(21))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(TagCorrectionCandidateErrorCode.TOO_MANY_OPERATIONS));
        verify(candidateRepository, never()).addJudgment(any());
        // 요청 형태만 보는 검사라 전역 교정 상태 잠금을 잡기 전에 끝나야 한다 — 거대한 본문이 다른 교정 경로를 막지 않게.
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("상한과 같은 20개는 저장한다")
    void acceptsOperationsAtRequestLimit() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        assertThat(service.create(command(true, 9L, operations(20))).evidenceIds())
                .hasSize(20);
    }

    @Test
    @DisplayName("이미 쌓인 판단과 이번 요청을 더해 신고당 누적 상한(50)을 넘으면 거부한다")
    void rejectsWhenAccumulatedJudgmentLimitExceeded() {
        reviewingTagCorrection();
        when(candidateRepository.countByFeedback(1L)).thenReturn(45);

        assertThatThrownBy(() -> service.create(command(true, 9L, operations(6))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(TagCorrectionCandidateErrorCode.JUDGMENT_LIMIT_EXCEEDED));
        verify(candidateRepository, never()).addJudgment(any());
    }

    @Test
    @DisplayName("누적이 상한에 정확히 맞으면 저장한다")
    void acceptsWhenAccumulatedJudgmentFitsLimit() {
        reviewingTagCorrection();
        when(candidateRepository.countByFeedback(1L)).thenReturn(45);
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        assertThat(service.create(command(true, 9L, operations(5))).evidenceIds())
                .hasSize(5);
    }

    @Test
    @DisplayName("같은 대기 판단이 이미 있으면 새로 넣지 않고 그 근거 id 를 요청 순서대로 돌려준다 (S15P21A501-317)")
    void reusesPendingJudgmentInsteadOfInserting() {
        reviewingTagCorrection();
        when(candidateRepository.findPendingJudgment(any())).thenAnswer(invocation -> {
            ReviewerTagJudgment judgment = invocation.getArgument(0);
            return "서울".equals(judgment.matchValue()) ? Optional.of(4001L) : Optional.empty();
        });
        when(candidateRepository.addJudgment(any())).thenReturn(5002L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true,
                9L,
                new TagOperation(TagCorrectionAction.REJECT, TagScope.SCENE, "location", "서울", "서울"),
                new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(result.evidenceIds()).containsExactly(4001L, 5002L);
        assertThat(result.newlyCreated()).isEqualTo(1);
        verify(candidateRepository, org.mockito.Mockito.times(1)).addJudgment(any());
    }

    @Test
    @DisplayName("한 요청 안의 같은 변경안 두 개는 근거 하나로 모인다 (S15P21A501-317)")
    void collapsesDuplicateOperationsInOneRequest() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);
        TagOperation same = new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도");

        CreateTagCorrectionCandidateResult result = service.create(command(true, 9L, same, same));

        assertThat(result.evidenceIds()).containsExactly(5001L, 5001L);
        assertThat(result.newlyCreated()).isEqualTo(1);
        verify(candidateRepository, org.mockito.Mockito.times(1)).addJudgment(any());
    }

    @Test
    @DisplayName("재사용하는 판단은 신고당 누적 상한(50)에 세지 않는다 (S15P21A501-317)")
    void reusedJudgmentsDoNotConsumeAccumulatedLimit() {
        reviewingTagCorrection();
        when(candidateRepository.countByFeedback(1L)).thenReturn(50);
        when(candidateRepository.findPendingJudgment(any())).thenReturn(Optional.of(4001L));

        CreateTagCorrectionCandidateResult result = service.create(command(true, 9L, operations(3)));

        assertThat(result.evidenceIds()).containsExactly(4001L, 4001L, 4001L);
        assertThat(result.newlyCreated()).isZero();
        verify(candidateRepository, never()).addJudgment(any());
    }

    @Test
    @DisplayName("같은 태그라도 판단(승인/반려)이나 범위가 다르면 재사용하지 않는다 (S15P21A501-317)")
    void differentJudgmentOrScopeIsNotCollapsed() {
        reviewingTagCorrection();
        when(candidateRepository.addJudgment(any())).thenReturn(5001L, 5002L, 5003L);

        CreateTagCorrectionCandidateResult result = service.create(command(
                true,
                9L,
                new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "서울", "서울"),
                new TagOperation(TagCorrectionAction.REJECT, TagScope.SCENE, "location", "서울", "서울"),
                new TagOperation(TagCorrectionAction.APPROVE, TagScope.CLIP, "location", "서울", "서울")));

        assertThat(result.evidenceIds()).containsExactly(5001L, 5002L, 5003L);
        assertThat(result.newlyCreated()).isEqualTo(3);
    }

    private TagOperation[] operations(int count) {
        TagOperation[] operations = new TagOperation[count];
        for (int i = 0; i < count; i++) {
            operations[i] =
                    new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "장소" + i, "장소" + i);
        }
        return operations;
    }
}
