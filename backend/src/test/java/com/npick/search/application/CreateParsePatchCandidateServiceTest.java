package com.npick.search.application;

import java.util.Collections;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.Mockito.clearInvocations;
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
    @DisplayName("통합 판정 correction 신고에서도 해석 규칙 후보를 만든다 (S15P21A501-281)")
    void acceptsUnifiedCorrectionResolution() {
        when(parseContextPort.find(1L))
                .thenReturn(Optional.of(new ParseContext("REVIEWING", "correction", 9L, RESOLVER_OUTPUT)));
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.searchRuleId()).isEqualTo(777L);
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
    @DisplayName("같은 규칙을 교체하는 대기 후보가 이미 있으면 새 후보를 거부한다 (S15P21A501-309)")
    void rejectsSecondCandidateReplacingSameRule() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.existsActivePatchParse(555L)).thenReturn(true);
        when(candidateRepository.existsPendingReplacing(1L, 555L)).thenReturn(true);
        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, 555L)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.REPLACES_CONFLICT));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("서로 다른 규칙을 교체하는 후보는 함께 허용한다 (S15P21A501-309)")
    void allowsCandidateReplacingDifferentRule() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.existsActivePatchParse(555L)).thenReturn(true);
        when(candidateRepository.existsPendingReplacing(1L, 555L)).thenReturn(false);
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, 555L));

        assertThat(outcome.created()).isTrue();
        verify(candidateRepository).insertIfAbsent(any());
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

    @Test
    @DisplayName("신고에 쌓인 후보가 상한(10)이면 새 후보를 거부한다")
    void rejectsWhenFeedbackCandidateLimitReached() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.countByFeedback(1L)).thenReturn(10);

        assertThatThrownBy(() -> service.create(command(true, 9L, CONDITION, PATCH, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ParseRuleCandidateErrorCode.CANDIDATE_LIMIT_EXCEEDED));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @Test
    @DisplayName("상한 직전(9)이면 후보를 저장한다")
    void createsJustBelowFeedbackCandidateLimit() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.countByFeedback(1L)).thenReturn(9);
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        assertThat(outcome.created()).isTrue();
    }

    @Test
    @DisplayName("상한에 닿았어도 같은 요청키 재요청은 거부하지 않고 기존 후보를 준다")
    void idempotentReplayIsNotRejectedAtLimit() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.of(555L));
        when(candidateRepository.countByFeedback(1L)).thenReturn(10);

        ParseCandidateOutcome outcome = service.create(command(true, 9L, CONDITION, PATCH, null));

        assertThat(outcome.searchRuleId()).isEqualTo(555L);
        assertThat(outcome.created()).isFalse();
    }

    // ── 후보 크기 상한 (S15P21A501-290) ──

    private static String condition(int predicates, String value) {
        String predicate = "{\"axis\":\"locations\",\"op\":\"has_value\",\"value\":\"" + value + "\"}";
        return "{\"syntax_version\":\"parse-rule/v1\",\"resolution_schema_version\":\"query-resolver/v2\",\"all\":["
                + String.join(",", Collections.nCopies(predicates, predicate)) + "]}";
    }

    private static String patch(int operations, String operation) {
        return "{\"syntax_version\":\"parse-rule/v1\",\"operations\":["
                + String.join(",", Collections.nCopies(operations, operation)) + "]}";
    }

    private static String removeItem(String value) {
        return "{\"op\":\"remove_item\",\"axis\":\"locations\",\"type\":\"location\",\"value\":\"" + value + "\"}";
    }

    private static String addItem(String value) {
        return "{\"op\":\"add_item\",\"axis\":\"incident_names\",\"value\":\"" + value + "\"}";
    }

    private static String addItemFrom(String value) {
        return "{\"op\":\"add_item\",\"axis\":\"incident_names\",\"value_from\":"
                + "{\"axis\":\"locations\",\"type\":\"location\",\"value\":\"" + value + "\"}}";
    }

    private void readyToInsert() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.empty());
        when(candidateRepository.insertIfAbsent(any())).thenReturn(Optional.of(777L));
    }

    private void assertTooLarge(String condition, String patch) {
        // 같은 테스트에서 앞서 통과시킨 경계값 호출의 저장 기록을 지우고, 이번 호출이 저장하지 않았는지만 본다.
        clearInvocations(candidateRepository);
        assertThatThrownBy(() -> service.create(command(true, 9L, condition, patch, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ParseRuleCandidateErrorCode.CANDIDATE_TOO_LARGE));
        verify(candidateRepository, never()).insertIfAbsent(any());
    }

    @ParameterizedTest(name = "조건 {0}개·연산 {1}개")
    @CsvSource({"10, 1", "1, 10", "10, 10"})
    @DisplayName("조건·연산이 각각 10개까지면 저장한다")
    void acceptsCountsAtLimit(int predicates, int operations) {
        readyToInsert();
        ParseCandidateOutcome outcome = service.create(
                command(true, 9L, condition(predicates, "○○공장"), patch(operations, removeItem("○○공장")), null));
        assertThat(outcome.created()).isTrue();
    }

    @Test
    @DisplayName("조건이 11개면 SRCH_400_203 으로 거부한다")
    void rejectsElevenPredicates() {
        readyToInsert();
        assertTooLarge(condition(11, "○○공장"), PATCH);
    }

    @Test
    @DisplayName("연산이 11개면 SRCH_400_203 으로 거부한다")
    void rejectsElevenOperations() {
        readyToInsert();
        assertTooLarge(CONDITION, patch(11, removeItem("○○공장")));
    }

    @Test
    @DisplayName("새로 적는 add_item 값은 20자(한글 1자 = 1)까지 저장하고 21자면 거부한다")
    void limitsNewValueLength() {
        readyToInsert();
        String twenty = "가".repeat(20);
        assertThat(service.create(command(true, 9L, CONDITION, patch(1, addItem(twenty)), null))
                        .created())
                .isTrue();
        assertTooLarge(CONDITION, patch(1, addItem(twenty + "가")));
    }

    @Test
    @DisplayName("조건 대조 값은 100자까지 저장하고 101자면 거부한다")
    void limitsPredicateValueLength() {
        readyToInsert();
        String hundred = "가".repeat(100);
        assertThat(service.create(command(true, 9L, condition(1, hundred), PATCH, null))
                        .created())
                .isTrue();
        assertTooLarge(condition(1, hundred + "가"), PATCH);
    }

    @Test
    @DisplayName("원본 항목을 가리키는 remove_item·value_from 값은 새 값 상한(20)이 아니라 대조 상한(100)을 따른다")
    void originalReferencingOperationValuesUseMatchLimit() {
        readyToInsert();
        String hundred = "가".repeat(100);
        assertThat(service.create(command(true, 9L, CONDITION, patch(1, removeItem(hundred)), null))
                        .created())
                .isTrue();
        assertThat(service.create(command(true, 9L, CONDITION, patch(1, addItemFrom(hundred)), null))
                        .created())
                .isTrue();
        assertTooLarge(CONDITION, patch(1, removeItem(hundred + "가")));
        assertTooLarge(CONDITION, patch(1, addItemFrom(hundred + "가")));
    }

    @Test
    @DisplayName("상한을 넘는 본문이어도 같은 요청키 재요청은 기존 후보를 준다")
    void idempotentReplayIsNotRejectedBySizeLimit() {
        reviewingPatchParse();
        when(candidateRepository.findId(1L, "rk-1")).thenReturn(Optional.of(555L));

        ParseCandidateOutcome outcome = service.create(command(true, 9L, condition(11, "○○공장"), PATCH, null));

        assertThat(outcome.searchRuleId()).isEqualTo(555L);
        assertThat(outcome.created()).isFalse();
    }
}
