package com.npick.tag.application;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
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
    private CreateTagCorrectionCandidateService service;

    @BeforeEach
    void setUp() {
        tagContextPort = mock(TagContextPort.class);
        candidateRepository = mock(TagCorrectionCandidateRepository.class);
        service = new CreateTagCorrectionCandidateService(tagContextPort, candidateRepository);
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

        List<Long> ids = service.create(command(
                true,
                9L,
                new TagOperation(TagCorrectionAction.REJECT, TagScope.SCENE, "location", "서울", "서울"),
                new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(ids).containsExactly(5001L, 5002L);
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
    @DisplayName("태그·해석 교정이 아닌 판정(장면 제외 등)은 거부한다")
    void rejectsNotTagCorrection() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "exclude_scene", 9L, 300L, 100L)));
        assertThatThrownBy(() -> service.create(command(
                        true,
                        9L,
                        new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도"))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(TagCorrectionCandidateErrorCode.NOT_TAG_CORRECTION));
    }

    @Test
    @DisplayName("patch_parse(태그·해석 모두 잘못) 신고에서도 태그 변경안을 만든다 (F-09)")
    void acceptsPatchParseResolution() {
        when(tagContextPort.find(1L))
                .thenReturn(Optional.of(new TagContext("REVIEWING", "patch_parse", 9L, 300L, 100L)));
        when(candidateRepository.addJudgment(any())).thenReturn(5001L);

        List<Long> ids = service.create(command(
                true, 9L, new TagOperation(TagCorrectionAction.APPROVE, TagScope.SCENE, "location", "제주도", "제주도")));

        assertThat(ids).containsExactly(5001L);
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
    }
}
