package com.npick.search.application.query.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.npick.search.application.port.CompleteSearchExecution;
import com.npick.search.application.query.candidate.SceneCandidateResult;
import com.npick.search.application.query.dense.DenseCandidatesResult;
import com.npick.search.application.query.guard.FalseHitGuardResult;
import com.npick.search.application.query.structured.StructuredScoresResult;
import com.npick.search.domain.model.ShortageReason;

/**
 * 순위 계산 결과를 실행 기록의 저장 형태로 옮긴다 (S15P21A501-60 의 {@code CompleteSearchExecution}).
 *
 * <p>기록 쪽이 채널 결과를 {@code Map} 으로 받기로 해서 변환이 필요하다. <b>변환을 한 곳에 모은 이유</b>는 여기서 빠뜨린 필드가 곧 「그때 무엇을 보고 이 순위가 나왔나」에 답할 수 없는
 * 구멍이 되기 때문이다. 흩어 두면 어느 채널이 덜 기록되는지 보이지 않는다.
 *
 * <p>계산에 쓴 값을 그대로 옮기고 여기서 다시 계산하지 않는다 (§7.2).
 */
final class SearchRecordPayload {

    private SearchRecordPayload() {}

    /**
     * 거르기 전 후보.
     *
     * <p>dense 가 {@code null} 이면 채널이 꺼져 있었다는 뜻이고 그대로 {@code null} 로 남긴다 — 껐다는 것과 돌렸는데 실패했다는 것은 다르고, 기록을 읽는 쪽이 그 둘을 구분해야
     * 한다.
     */
    static CompleteSearchExecution.CandidateRecord candidates(SearchCandidates candidates) {
        var query = candidates.candidates();
        List<Map<String, Object>> lexical = new ArrayList<>();
        for (SceneCandidateResult candidate : query.lexicalCandidates()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("scene_id", Long.toString(candidate.sceneId()));
            value.put("clip_id", Long.toString(candidate.clipId()));
            value.put("score", candidate.score());
            value.put("text_score", candidate.textScore());
            value.put("ocr_score", candidate.ocrScore());
            lexical.add(value);
        }
        return new CompleteSearchExecution.CandidateRecord(
                lexical, dense(query.denseCandidates()), structured(query.structuredScores()));
    }

    private static Map<String, Object> dense(DenseCandidatesResult dense) {
        if (dense == null) {
            return null;
        }
        var hits = new ArrayList<Map<String, Object>>();
        for (DenseCandidatesResult.Candidate candidate : dense.candidates()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("scene_id", Long.toString(candidate.sceneId()));
            value.put("clip_id", Long.toString(candidate.clipId()));
            value.put("rank", candidate.rank());
            value.put("similarity", candidate.similarity());
            value.put("distance", candidate.distance());
            hits.add(value);
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("status", dense.status().name());
        value.put("reason", dense.reason().name());
        value.put("candidates", hits);
        return value;
    }

    private static Map<String, Object> structured(StructuredScoresResult structured) {
        var scored = new ArrayList<Map<String, Object>>();
        for (StructuredScoresResult.SceneScore scene : structured.scenes()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("scene_id", Long.toString(scene.sceneId()));
            value.put("score", scene.score());
            value.put("denominator", scene.denominator());
            scored.add(value);
        }
        var ineligible = new ArrayList<Map<String, Object>>();
        for (StructuredScoresResult.Ineligible scene : structured.ineligibleScenes()) {
            var value = new LinkedHashMap<String, Object>();
            value.put("scene_id", Long.toString(scene.sceneId()));
            value.put("reason", scene.reason().name());
            ineligible.add(value);
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("scored", scored);
        value.put("ineligible", ineligible);
        return value;
    }

    /**
     * 뺀 것들과 뺀 이유, 그리고 실제 반환 수.
     *
     * <p>통과한 장면의 판정도 함께 넣는다. 제외 건만 남기면 「왜 이건 살아남았나」에 답할 수 없고, -56 이 통과 장면에도 3값 판정을 남기게 만든 이유가 사라진다.
     */
    static CompleteSearchExecution.FilterRecord filtered(SearchCandidates candidates) {
        var verdicts = new ArrayList<CompleteSearchExecution.GuardVerdict>();
        for (FalseHitGuardResult.SceneVerdict verdict : candidates.guard().verdicts()) {
            verdicts.add(new CompleteSearchExecution.GuardVerdict(
                    verdict.sceneId(),
                    verdict.exclusionReason() == null
                            ? null
                            : verdict.exclusionReason().wireValue(),
                    verdict.fields().stream()
                            .map(field -> field.field().name() + ":"
                                    + field.judgment().name())
                            .toList()));
        }
        return new CompleteSearchExecution.FilterRecord(
                candidates.scenes().size(),
                candidates.shortageReasons().stream()
                        .map(ShortageReason::wireValue)
                        .toList(),
                new CompleteSearchExecution.GuardRecord(candidates.guard().incidentGuardActive(), verdicts));
    }

    /** 승인된 장면 제외. 제외는 여러 규칙이 겹칠 수 있어 장면마다 규칙 목록으로 남긴다. */
    static List<CompleteSearchExecution.AppliedSceneExclusion> appliedExcludes(SearchCandidates candidates) {
        return candidates.appliedExcludes().stream()
                .map(excluded ->
                        new CompleteSearchExecution.AppliedSceneExclusion(excluded.sceneId(), excluded.ruleIds()))
                .toList();
    }
}
