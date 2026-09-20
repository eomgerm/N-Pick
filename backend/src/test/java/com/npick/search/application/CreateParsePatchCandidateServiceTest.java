package com.npick.search.application;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.npick.common.error.BusinessException;
import com.npick.common.persistence.CorrectionStateLock;
import com.npick.search.application.error.ParseRuleCandidateErrorCode;
import com.npick.search.application.port.ParseContext;
import com.npick.search.application.port.ParseContextPort;
import com.npick.search.domain.model.ParseRuleCandidate;
import com.npick.search.domain.repository.ParseRuleCandidateRepository;
import com.npick.search.infrastructure.persistence.mapper.ParseRuleJsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateParsePatchCandidateServiceTest {

    private static final String RESOLVER_OUTPUT =
            "{\"schema_version\":\"query-resolver/v2\",\"intent\":\"scene_search\"}";
    private static final String CONDITION =
            "{\"syntax_version\":\"parse-rule/v1\",\"resolution_schema_version\":\"query-resolver/v2\","
                    + "\"all\":[{\"axis\":\"locations\",\"op\":\"has_value\",\"value\":\"○○공장\"}]}";
    private static final String PATCH = "{\"syntax_version\":\"parse-rule/v1\",\"operations\":"
            + "[{\"op\":\"remove_item\",\"axis\":\"locations\",\"type\":\"location\",\"value\":\"○○공장\"}]}";

    private ParseContextPort parseContextPort;
    private ParseRuleCandidateRepository candidateRepository;
    private CorrectionStateLock correctionStateLock;
    private CreateParsePatchCandidateService service;

    @BeforeEach
    void setUp() {
        parseContextPort = mock(ParseContextPort.class);
        candidateRepository = mock(ParseRuleCandidateRepository.class);
        correctionStateLock = mock(CorrectionStateLock.class);
        service = new CreateParsePatchCandidateService(
                parseContextPort, candidateRepository, new ParseRuleJsonMapper(), correctionStateLock);
    }

    private void reviewingPatchParse() {
        when(parseContextPort.find(1L))
                .thenReturn(Optional.of(new ParseContext("REVIEWING", "patch_parse", 9L, RESOLVER_OUTPUT)));
    }

    private CreateParsePatchCandidateCommand command(
            boolean reviewerRole, long reviewerId, String condition, String patch, Long replacesRuleId) {
        return new CreateParsePatchCandidateCommand(
                1L, reviewerId, reviewerRole, "rk-1", condition, patch, replacesRuleId);
    }

    @Test
    @DisplayName("전제·본문이 맞으면 비활성 후보로 저장하고 created 결과를 준다")
    void createsInactiveCandidate() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        verify(correctionStateLock).acquire();
        assertThat(outcome.searchRuleId()).isEqualTo(777L);
        assertThat(outcome.created()).isTrue();
        ArgumentCaptor<ParseRuleCandidate> captor = ArgumentCaptor.forClass(ParseRuleCandidate.class);
        verify(candidateRepository).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().conditionJson()).contains("parse-rule/v1");
    }

    @Test
    @DisplayName("편집기자면 거부한다")
    void rejectsEditor() {
        assertThatThrownBy(() -> service.create(command(false, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.EDITOR_FORBIDDEN));
        verify(candidateRepository, never()).insertIfAbsent(any());
        verify(correctionStateLock, never()).acquire();
    }

    @Test
    @DisplayName("없는 신고면 거부한다")
    void rejectsMissingFeedback() {
        when(parseContextPort.find(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.FEEDBACK_NOT_FOUND));
    }

    @Test
    @DisplayName("검수 중이 아니면 거부한다")
    void rejectsNotReviewing() {
        when(parseContextPort.find(1L))
                .thenReturn(Optional.of(new ParseContext("CLOSED", "patch_parse", 9L, RESOLVER_OUTPUT)));
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.NOT_REVIEWING));
    }

    @Test
    @DisplayName("해석 교정으로 처리된 신고가 아니면 거부한다")
    void rejectsNotPatchParse() {
        when(parseContextPort.find(1L))
                .thenReturn(Optional.of(new ParseContext("REVIEWING", "no_action", 9L, RESOLVER_OUTPUT)));
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.NOT_PATCH_PARSE));
    }

    @Test
    @DisplayName("담당 검수자가 아니면 거부한다")
    void rejectsNonOwnerReviewer() {
        reviewingPatchParse();
        assertThatThrownBy(() -> service.create(command(true, 7L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.NOT_REVIEWER));
    }

    @Test
    @DisplayName("원 검색에 resolver 출력이 없으면 본문 오류가 아니라 전제 부재로 거부한다")
    void rejectsWhenResolverOutputAbsent() {
        when(parseContextPort.find(1L)).thenReturn(Optional.of(new ParseContext("REVIEWING", "patch_parse", 9L, null)));
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.RESOLVER_OUTPUT_ABSENT));
    }

    @Test
    @DisplayName("본문의 출력 계약이 원 해석과 다르면 거부한다")
    void rejectsIncompatibleCandidate() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        String staleCondition = CONDITION.replace("query-resolver/v2", "query-resolver/v1");
        assertThatThrownBy(() -> service.create(command(true, 9L, staleCondition, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.INVALID_CANDIDATE));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("교체 대상이 활성 patch_parse 규칙이 아니면 거부한다")
    void rejectsWhenReplacesTargetInvalid() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.existsActivePatchParse(555L)).thenReturn(false);
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, 555L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.REPLACES_NOT_FOUND));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("같은 요청키로 다시 부르면 저장하지 않고 기존 후보를 existing 으로 준다")
    void idempotentReturnsExisting() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.of(555L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        assertThat(outcome.searchRuleId()).isEqualTo(555L);
        assertThat(outcome.created()).isFalse();
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("동시 저장으로 유니크 위반이 나면 500 이 아니라 기존 후보를 existing 으로 복구한다")
    void recoversFromConcurrentDuplicate() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(999L));
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.empty());

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        assertThat(outcome.searchRuleId()).isEqualTo(999L);
        assertThat(outcome.created()).isFalse();
    }
}
