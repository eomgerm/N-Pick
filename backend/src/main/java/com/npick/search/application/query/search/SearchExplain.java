package com.npick.search.application.query.search;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.npick.search.application.query.card.SceneCard;
import com.npick.search.domain.model.ShotType;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagType;

/**
 * 살아남은 장면 하나를 응답 카드와 {@code explain_json} 으로 옮긴다.
 *
 * <p><b>두 산출물을 한 곳에서 만드는 것이 핵심이다.</b> 응답과 기록이 갈리면 나중에 「그때 화면에 뭐가 떴나」를 기록으로 확인할 수 없다. 같은 값에서 모양만 달리 뽑는다.
 *
 * <p>여기서 태그를 다시 읽지 않는다. 검색 당시의 {@code EffectiveTag} 를 그대로 받아 쓴다 — 나중에 다시 읽으면 그 사이 교정이 반영돼 과거 근거가 바뀐다 (FRD §7.2 「지금의 교정
 * 상태로 다시 계산해 덮어쓰지 않는다」).
 */
final class SearchExplain {

    /** 계약 §5.1 이 닫아 둔 {@code match_evidence.field} 어휘. */
    private static final String FIELD_CAPTION = "caption";

    private static final String FIELD_TRANSCRIPT = "transcript";
    private static final String FIELD_OCR = "ocr";
    private static final String FIELD_TAG = "tag";

    private SearchExplain() {}

    static SearchExecutionResult.ResultCard card(
            SearchCandidates.ScoredScene scene, int rank, Long resultId, List<String> queryTokens) {
        SceneCard card = scene.card();
        return new SearchExecutionResult.ResultCard(
                resultId,
                scene.sceneId(),
                scene.clipId(),
                rank,
                card.clipTitle(),
                card.caption(),
                card.startTimeMs(),
                card.endTimeMs(),
                date(scene.tags(), TagType.BROADCAST_DATE),
                date(scene.tags(), TagType.FILMED_DATE),
                shotType(card.shotType()),
                firstTagName(scene.tags(), TagType.SCENE_TYPE),
                matchedKeywords(scene, queryTokens),
                evidence(scene, queryTokens));
    }

    static Map<String, Object> score(SearchCandidates.ScoredScene scene) {
        var score = new LinkedHashMap<String, Object>();
        score.put("base_score", scene.score().baseScore());
        score.put("normalized_rrf", scene.score().normalizedRrf());
        score.put("structured_score", scene.score().structuredScore());
        score.put("structured_contribution", scene.score().structuredContribution());
        score.put("soft_score", scene.soft().softScore());
        var signals = new LinkedHashMap<String, Object>();
        scene.soft().signals().forEach((signal, value) -> signals.put(signal.name(), value));
        score.put("signals", signals);
        var channels = new ArrayList<Map<String, Object>>();
        for (var contribution : scene.score().channels()) {
            var channel = new LinkedHashMap<String, Object>();
            channel.put("channel", contribution.channel().name());
            channel.put("state", contribution.state().name());
            channel.put("rank", contribution.rank());
            channel.put("weight", contribution.weight());
            channel.put("contribution", contribution.contribution());
            channels.add(channel);
        }
        score.put("channels", channels);
        return score;
    }

    static Map<String, Object> match(SearchCandidates.ScoredScene scene, List<String> queryTokens) {
        var match = new LinkedHashMap<String, Object>();
        match.put("matched_keywords", matchedKeywords(scene, queryTokens));
        var evidence = new ArrayList<Map<String, Object>>();
        for (SearchExecutionResult.MatchEvidence item : evidence(scene, queryTokens)) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("field", item.field());
            entry.put("value", item.value());
            entry.put("source", item.source());
            entry.put("verification_status", item.verificationStatus());
            evidence.add(entry);
        }
        match.put("match_evidence", evidence);
        return match;
    }

    static Map<String, Object> guard(SearchCandidates.ScoredScene scene) {
        var guard = new LinkedHashMap<String, Object>();
        // 살아남았으므로 항상 null 이다. 그래도 키를 빼지 않는다 — 읽는 쪽이 "판정이 없었다" 와
        // "통과했다" 를 구분할 수 있어야 한다.
        guard.put(
                "exclusion_reason",
                scene.verdict().exclusionReason() == null
                        ? null
                        : scene.verdict().exclusionReason().wireValue());
        var fields = new ArrayList<Map<String, Object>>();
        for (var judgment : scene.verdict().fields()) {
            var field = new LinkedHashMap<String, Object>();
            field.put("field", judgment.field().name());
            field.put("judgment", judgment.judgment().name());
            // 기록의 ID 는 전부 문자열이다. explain_json 은 FE 까지 그대로 나가므로, 여기만 숫자로
            // 두면 TSID 가 2^53 을 넘는 순간 JavaScript 에서 값이 뭉개진다.
            field.put(
                    "grounding_tag_ids",
                    judgment.groundingTagIds().stream().map(String::valueOf).toList());
            fields.add(field);
        }
        guard.put("fields", fields);
        return guard;
    }

    static Map<String, Object> display(SearchCandidates.ScoredScene scene) {
        SceneCard card = scene.card();
        var display = new LinkedHashMap<String, Object>();
        display.put("display_name", card.clipTitle());
        display.put("scene_description", card.caption());
        display.put("start_time_ms", card.startTimeMs());
        display.put("end_time_ms", card.endTimeMs());
        display.put("shot_type", card.shotType());
        display.put("scene_type", firstTagName(scene.tags(), TagType.SCENE_TYPE));
        display.put("broadcast_date", dateEntry(date(scene.tags(), TagType.BROADCAST_DATE)));
        display.put("filmed_date", dateEntry(date(scene.tags(), TagType.FILMED_DATE)));
        return display;
    }

    private static Map<String, Object> dateEntry(SearchExecutionResult.DateValue value) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("value", value.value() == null ? null : value.value().toString());
        entry.put("verification_status", value.verificationStatus());
        return entry;
    }

    /**
     * 날짜 태그 하나를 값과 검증 상태로 옮긴다.
     *
     * <p>값이 없으면 상태는 {@code unknown} 이다 (§5.1 불변식). 태그가 여럿이면 검증된 것을 앞세운다 — 미검증 값을 대표로 내보내면 F-06 의 「검증된 날짜 충돌」 판정과 화면이 서로
     * 다른 날짜를 말하게 된다.
     */
    private static SearchExecutionResult.DateValue date(List<EffectiveTag> tags, TagType type) {
        EffectiveTag chosen = null;
        for (EffectiveTag tag : tags) {
            if (tag.tagType() != type) continue;
            if (chosen == null
                    || (!chosen.verification().trustedForConflict()
                            && tag.verification().trustedForConflict())) {
                chosen = tag;
            }
        }
        if (chosen == null) {
            return SearchExecutionResult.DateValue.unknown();
        }
        LocalDate value = parseDate(chosen.matchValue());
        if (value == null) {
            return SearchExecutionResult.DateValue.unknown();
        }
        return new SearchExecutionResult.DateValue(
                value, chosen.verification().trustedForConflict() ? "verified" : "unverified");
    }

    /**
     * 날짜 태그의 {@code match_value} 는 DB 제약이 {@code YYYY-MM-DD} 로 막고 있다. 그래도 파싱 실패를 예외로 올리지 않는다 — 태그 하나 때문에 검색 전체가 500 이
     * 되는 것보다 그 날짜를 미상으로 두는 편이 낫다.
     */
    private static LocalDate parseDate(String matchValue) {
        try {
            return LocalDate.parse(matchValue);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    /**
     * 계약이 닫아 둔 4값 밖이면 {@code unknown} 으로 좁힌다 (§5.1).
     *
     * <p>이 접기를 표현 계층이 아니라 여기서 하는 이유는 presentation 이 domain 의 {@code ShotType} 을 알지 못하게 하기 위해서다 (설계 정본 §4). 기록에는
     * {@code display.shot_type} 으로 저장값 원문이 그대로 남으므로 당시 값을 잃지 않는다.
     */
    private static String shotType(String stored) {
        return ShotType.parse(stored).map(ShotType::storedValue).orElse("unknown");
    }

    private static String firstTagName(List<EffectiveTag> tags, TagType type) {
        return tags.stream()
                .filter(tag -> tag.tagType() == type)
                .map(EffectiveTag::name)
                .findFirst()
                .orElse(null);
    }

    /**
     * 질의 토큰 중 이 장면의 색인 토큰에 실제로 있던 것.
     *
     * <p>점수 채널은 장면당 점수 하나만 주므로 무엇이 걸렸는지 알려 주지 않는다. 카드가 「서울역 때문에 나왔다」를 말하려면 여기서 다시 맞춰 봐야 한다.
     */
    private static List<String> matchedKeywords(SearchCandidates.ScoredScene scene, List<String> queryTokens) {
        Set<String> indexed = new LinkedHashSet<>(scene.card().captionTokens());
        indexed.addAll(scene.card().transcriptTokens());
        scene.card().ocrTexts().forEach(ocr -> indexed.addAll(ocr.tokens()));
        return queryTokens.stream()
                .filter(indexed::contains)
                .map(SearchExplain::stripPosTag)
                .distinct()
                .toList();
    }

    /**
     * 색인 토큰은 {@code 형태/품사}(예: {@code 비/NNG}) 라 동형이의를 가른다. 화면 칩에는 사람이 친 검색어인 형태만 보인다. 형태소는 기호(S*)를
     * 색인에서 걸러 {@code /} 가 형태에 들어오지 않으므로 마지막 {@code /} 앞이 형태다. 옛 형식(형태만)이나 태그 없는 값은 그대로 둔다.
     */
    private static String stripPosTag(String token) {
        int slash = token.lastIndexOf('/');
        return slash < 0 ? token : token.substring(0, slash);
    }

    /**
     * 무엇이 어디서 걸렸는가. 계약이 {@code match_evidence} 를 1개 이상으로 요구한다 (§5.1).
     *
     * <p>텍스트 셋은 검증 개념이 없어 {@code unverified} 다. 태그만 {@code tag_evidence.verification_status} 를 그대로 싣는다 — 날짜·사건명 충돌 판정이
     * 그 값을 근거로 쓰기 때문에 화면도 같은 값을 보여야 한다.
     */
    private static List<SearchExecutionResult.MatchEvidence> evidence(
            SearchCandidates.ScoredScene scene, List<String> queryTokens) {
        List<SearchExecutionResult.MatchEvidence> evidence = new ArrayList<>();
        SceneCard card = scene.card();
        Set<String> tokens = new LinkedHashSet<>(queryTokens);

        if (hits(tokens, card.captionTokens())) {
            evidence.add(new SearchExecutionResult.MatchEvidence(
                    FIELD_CAPTION, card.caption(), "scene_caption", "unverified"));
        }
        if (hits(tokens, card.transcriptTokens())) {
            evidence.add(new SearchExecutionResult.MatchEvidence(
                    FIELD_TRANSCRIPT, card.transcriptText(), "scene_transcript", "unverified"));
        }
        for (SceneCard.OcrText ocr : card.ocrTexts()) {
            if (hits(tokens, ocr.tokens())) {
                evidence.add(new SearchExecutionResult.MatchEvidence(
                        FIELD_OCR, ocr.rawText(), "keyframe_ocr", "unverified"));
            }
        }
        for (EffectiveTag tag : scene.tags()) {
            if (tokens.contains(tag.matchValue())) {
                evidence.add(new SearchExecutionResult.MatchEvidence(
                        FIELD_TAG,
                        tag.name(),
                        tag.source(),
                        tag.verification().trustedForConflict() ? "verified" : "unverified"));
            }
        }
        if (evidence.isEmpty()) {
            // 순위에 오른 이상 무언가는 걸렸다. 토큰 대조로 되짚지 못하는 경로(dense·구조화 점수)로
            // 올라온 장면이면 설명을 근거로 내보낸다 — 계약이 빈 배열을 허용하지 않는다.
            evidence.add(fallbackEvidence(card, scene.tags()));
        }
        return evidence;
    }

    /**
     * 토큰 대조로 되짚지 못한 장면의 근거.
     *
     * <p>순위에 오른 이상 무언가는 걸렸다 — dense 나 구조화 점수로 올라온 경우다. 계약이 빈 배열을 허용하지 않으므로(§5.1) 설명·대사·화면 글자 중 <b>있는 것</b>을 내보낸다. 셋 다
     * 없으면 걸린 태그라도 싣는다. 여기서 caption 만 보면 설명 없는 장면이 빈 배열로 나가 계약이 깨진다.
     */
    private static SearchExecutionResult.MatchEvidence fallbackEvidence(SceneCard card, List<EffectiveTag> tags) {
        if (card.caption() != null) {
            return new SearchExecutionResult.MatchEvidence(
                    FIELD_CAPTION, card.caption(), "scene_caption", "unverified");
        }
        if (card.transcriptText() != null) {
            return new SearchExecutionResult.MatchEvidence(
                    FIELD_TRANSCRIPT, card.transcriptText(), "scene_transcript", "unverified");
        }
        if (!card.ocrTexts().isEmpty()) {
            return new SearchExecutionResult.MatchEvidence(
                    FIELD_OCR, card.ocrTexts().getFirst().rawText(), "keyframe_ocr", "unverified");
        }
        if (!tags.isEmpty()) {
            EffectiveTag tag = tags.getFirst();
            return new SearchExecutionResult.MatchEvidence(
                    FIELD_TAG,
                    tag.name(),
                    tag.source(),
                    tag.verification().trustedForConflict() ? "verified" : "unverified");
        }
        // 텍스트도 태그도 없다. dense 벡터 유사도만으로 올라온 장면이고, 사람이 읽을 근거가
        // 실제로 존재하지 않는다. 지어내지 않고 null 로 둔다 — 없는 근거를 만들어 보이면
        // 사용자가 그 문자열을 근거로 판단한다 (§5.1 이 이 한 경우를 위해 value 를 nullable 로 둔다).
        return new SearchExecutionResult.MatchEvidence(FIELD_TAG, null, "dense_similarity", "unverified");
    }

    private static boolean hits(Set<String> tokens, List<String> indexed) {
        return indexed.stream().anyMatch(tokens::contains);
    }
}
