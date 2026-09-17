package com.npick.search.application.query.soft;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.npick.search.application.query.fusion.FusionResult;
import com.npick.search.domain.model.QueryResolution;
import com.npick.search.domain.model.ShotType;
import com.npick.search.domain.model.SoftRankingSettings;
import com.npick.search.domain.model.SoftSignal;
import com.npick.search.domain.policy.SoftRankingPolicy;
import com.npick.tag.application.query.ResolveSceneTagsUseCase;
import com.npick.tag.domain.model.EffectiveTag;
import com.npick.tag.domain.model.TagMatchValue;
import com.npick.tag.domain.model.TagType;

@Service
public class SoftRankingService implements AdjustSoftRankingUseCase {

    private final FindSceneShotTypesQueryPort shotTypes;
    private final ResolveSceneTagsUseCase tags;
    private final SoftRankingSettings settings;
    private final SoftRankingPolicy policy = new SoftRankingPolicy();

    public SoftRankingService(
            FindSceneShotTypesQueryPort shotTypes, ResolveSceneTagsUseCase tags, SoftRankingSettings settings) {
        this.shotTypes = shotTypes;
        this.tags = tags;
        this.settings = settings;
    }

    /**
     * 샷 유형과 유효 태그를 같은 스냅샷으로 읽는다. 상위 검색 트랜잭션이 있으면 그대로 참여한다.
     *
     * <p>상위 검색 트랜잭션이 있으면 여기 적은 격리 수준은 적용되지 않고 상위를 따른다. 「같은 스냅샷」은 그 상위도 같은 수준일 때 성립한다 — -52 와 같은 전제다.
     *
     * <p>활성 신호가 필요로 하는 조회만 한다 — soft 를 꺼 둔 실행이 매 검색마다 두 번씩 DB 를 더 때리지 않게 한다.
     *
     * <p>ponytail: 태그 판정을 -52 에 이어 두 번 부른다. 조립(-59)이 생기면 -52 가 이미 읽은 맵을 넘겨받는 쪽으로 바꾼다.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SoftRankingResult adjust(AdjustSoftRankingQuery query) {
        Objects.requireNonNull(query, "query");
        var resolution = query.finalResolution();
        Set<String> seasons = conditionValues(resolution, QueryResolution.ClassificationType.SEASON);
        Set<String> weathers = conditionValues(resolution, QueryResolution.ClassificationType.WEATHER);
        Set<SoftSignal> active = activeSignals(resolution, seasons, weathers);

        List<Long> sceneIds = query.candidates().stream()
                .map(FusionResult.ScoredCandidate::sceneId)
                .sorted()
                .distinct()
                .toList();
        boolean lookup = !active.isEmpty() && !sceneIds.isEmpty();
        Map<Long, ShotType> shots = lookup && active.contains(SoftSignal.B_ROLL) ? shotTypes.find(sceneIds) : Map.of();
        Map<Long, List<EffectiveTag>> effective = lookup && needsTags(active) ? tags.resolve(sceneIds) : Map.of();

        Map<Long, LocalDate> broadcastDates =
                active.contains(SoftSignal.RECENCY) ? latestBroadcastDates(effective) : Map.of();
        DateSpan span = DateSpan.of(broadcastDates.values());

        var ordered = query.candidates().stream()
                .map(candidate -> {
                    var signals = signalValues(
                            candidate.sceneId(), active, shots, effective, broadcastDates, span, seasons, weathers);
                    return new SoftRankingResult.OrderedCandidate(
                            candidate.sceneId(),
                            candidate.clipId(),
                            candidate.baseScore(),
                            policy.softScore(signals, settings),
                            signals);
                })
                .sorted(byRelevanceThenSoftSignals())
                .toList();
        return new SoftRankingResult(ordered, settings);
    }

    /**
     * 관련성이 먼저다. 보조 점수는 {@link SoftRankingPolicy#compareBase} 가 동점으로 본 구간 안에서만 순서를 가른다.
     *
     * <p>마지막 {@code sceneId} 비교가 결정성을 준다 — -52 가 장면당 한 행을 주므로 같은 실행을 두 번 돌려도 순서가 달라지지 않는다. 같은 {@code sceneId} 가 두 번 들어오면
     * 그 둘 사이에만 0 이 되고, 정렬이 stable 이라 입력 순서가 유지된다.
     */
    private Comparator<SoftRankingResult.OrderedCandidate> byRelevanceThenSoftSignals() {
        return (left, right) -> {
            int base = policy.compareBase(right.baseScore(), left.baseScore(), settings.tieEpsilon());
            if (base != 0) return base;
            int soft = Double.compare(right.softScore(), left.softScore());
            return soft != 0 ? soft : Long.compare(left.sceneId(), right.sceneId());
        };
    }

    /**
     * 이 질의에서 실제로 켜지는 신호.
     *
     * <p><b>{@code broadcast_date} 윈도가 함께 와도 최신성을 끄지 않는다.</b> 그 태그는 구조화 축에도 쓰이지만 두 곳이 세는 것이 다르다 — 축은
     * {@code matched/requested} 로 <b>윈도 안이냐 밖이냐</b>만 가르는 이진값이고, 최신성은 후보 집합 안에서의 <b>상대 순서</b>다. 끄면
     * 「9월 방송분 중 최근 것」처럼 둘이 함께 오는 가장 전형적인 질의에서 윈도 안 후보가 전원 축 만점이라 서로 구분되지 않는데 최신성마저 사라져 정렬이 {@code sceneId} 로
     * 떨어진다. F-05 「같은 개체를 중복 계산하지 않는다」가 막는 것은 한 점수 안에서 같은 값을 두 번 더하는 것이고, 여기는 항이 다르며 보조 점수는 {@code baseScore} 를
     * 뒤집지도 못한다.
     *
     * <p>가중치가 0 이면 꺼진 것이고, 질의가 그 조건을 묻지 않았어도 꺼진 것이다. 후자가 없으면 최신성·계절·날씨가 모든 질의에 상시로 깔린다. {@link SoftSignal#B_ROLL} 만 조건
     * 없이 켜지는데, 그 근거는 {@code SoftSignal} 의 주석에 있다.
     */
    private Set<SoftSignal> activeSignals(QueryResolution resolution, Set<String> seasons, Set<String> weathers) {
        var active = EnumSet.noneOf(SoftSignal.class);
        if (settings.isActive(SoftSignal.RECENCY) && resolution.intent() == QueryResolution.Intent.RECENT_SCENE) {
            active.add(SoftSignal.RECENCY);
        }
        if (settings.isActive(SoftSignal.B_ROLL)) {
            active.add(SoftSignal.B_ROLL);
        }
        if (settings.isActive(SoftSignal.SEASON) && !seasons.isEmpty()) {
            active.add(SoftSignal.SEASON);
        }
        if (settings.isActive(SoftSignal.WEATHER) && !weathers.isEmpty()) {
            active.add(SoftSignal.WEATHER);
        }
        return active;
    }

    private static boolean needsTags(Set<SoftSignal> active) {
        return active.contains(SoftSignal.RECENCY)
                || active.contains(SoftSignal.SEASON)
                || active.contains(SoftSignal.WEATHER);
    }

    /**
     * 활성 신호마다 {@code [0,1]} 값 하나.
     *
     * <p>값이 없어도 {@code 0} 으로 담는다 — 빼면 정보가 없는 항목이 가점을 받는 것과 같아진다 (F-05). 계절·날씨·B-roll 이 0 또는 1 인 것은 의도한 것이다. 같은 축의 태그가 몇
     * 개든 한 번만 세어 「같은 개체를 중복 계산하지 않는다」를 지킨다.
     */
    private static Map<SoftSignal, Double> signalValues(
            long sceneId,
            Set<SoftSignal> active,
            Map<Long, ShotType> shots,
            Map<Long, List<EffectiveTag>> effective,
            Map<Long, LocalDate> broadcastDates,
            DateSpan span,
            Set<String> seasons,
            Set<String> weathers) {
        var values = new EnumMap<SoftSignal, Double>(SoftSignal.class);
        for (SoftSignal signal : active) {
            double value =
                    switch (signal) {
                        case RECENCY -> span.normalize(broadcastDates.get(sceneId));
                        case B_ROLL -> shots.get(sceneId) == ShotType.B_ROLL ? 1 : 0;
                        case SEASON -> matches(effective.get(sceneId), TagType.SEASON, seasons) ? 1 : 0;
                        case WEATHER -> matches(effective.get(sceneId), TagType.WEATHER, weathers) ? 1 : 0;
                    };
            values.put(signal, value);
        }
        return values;
    }

    private static boolean matches(List<EffectiveTag> sceneTags, TagType type, Set<String> conditions) {
        return sceneTags != null
                && sceneTags.stream().anyMatch(tag -> tag.tagType() == type && conditions.contains(tag.matchValue()));
    }

    /**
     * 질의가 건 분류 조건의 정규화 값.
     *
     * <p>{@code tag.match_value} 와 같은 규칙으로 접는다. 규칙이 갈리면 같은 값을 가리키는 두 표기가 조용히 0 건이 된다 (F-04).
     */
    private static Set<String> conditionValues(QueryResolution resolution, QueryResolution.ClassificationType type) {
        return resolution.classifications().stream()
                .filter(classification -> classification.type() == type)
                .map(classification -> TagMatchValue.normalize(classification.value()))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 장면별 가장 늦은 방송일.
     *
     * <p>한 장면에 방송일 태그가 여러 개일 수 있다. 가장 늦은 것 하나만 쓴다 — 전부 더하면 태그가 많은 장면이 유리해져 「같은 개체를 중복 계산하지 않는다」를 깬다.
     *
     * <p>{@code match_value} 는 날짜 태그에 {@code YYYY-MM-DD} 가 {@code CHECK} 로 강제되므로 문자열 최댓값이 곧 가장 늦은 날짜다.
     */
    private static Map<Long, LocalDate> latestBroadcastDates(Map<Long, List<EffectiveTag>> effective) {
        var latest = new java.util.HashMap<Long, LocalDate>();
        effective.forEach((sceneId, sceneTags) -> sceneTags.stream()
                .filter(tag -> tag.tagType() == TagType.BROADCAST_DATE)
                .map(EffectiveTag::matchValue)
                .max(Comparator.naturalOrder())
                .ifPresent(value -> latest.put(sceneId, LocalDate.parse(value))));
        return latest;
    }

    /**
     * 후보 집합 안에서의 방송일 폭. 최신성을 {@code [0,1]} 로 접는 척도다.
     *
     * <p>후보 집합 기준이라 질의 사이의 절대 비교는 되지 않는다. 이 값은 <b>같은 실행의 동점 구간을 가르는 데만</b> 쓰이므로 절대 척도가 필요하지 않다.
     */
    private record DateSpan(long min, long max) {

        static DateSpan of(java.util.Collection<LocalDate> dates) {
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (LocalDate date : dates) {
                long day = date.toEpochDay();
                min = Math.min(min, day);
                max = Math.max(max, day);
            }
            return new DateSpan(min, max);
        }

        /** 날짜가 없으면 0 이다. 폭이 0 이면 구분할 것이 없으므로 전부 0 이다 — 전원 만점을 주면 정보가 없는 장면만 상대적으로 벌을 받는다. */
        double normalize(LocalDate date) {
            if (date == null || max <= min) return 0;
            return (double) (date.toEpochDay() - min) / (max - min);
        }
    }
}
