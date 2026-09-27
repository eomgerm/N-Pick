package com.npick.tag.application.query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.npick.tag.domain.model.TagJudgment;
import com.npick.tag.domain.model.TagType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 판정기 진입점의 계약. 여기서 널을 삼키면 아래 두 계층의 널 가드가 무력해진다. */
class SceneTagResolutionServiceTest {

    private final RecordingPort port = new RecordingPort();
    private final SceneTagResolutionService service = new SceneTagResolutionService(port);

    @Test
    @DisplayName("null 을 삼키지 않는다 - 유일한 진입점이 널 가드를 무력화하면 배선 실수가 태그 없음으로 위장된다")
    void doesNotSwallowNull() {
        assertThatThrownBy(() -> service.resolve(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.find(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("빈 입력은 DB 를 부르지 않는다")
    void emptyInputDoesNotQuery() {
        assertThat(service.resolve(List.of())).isEmpty();
        assertThat(service.resolveForRun(10, 20, List.of())).isEmpty();
        assertThat(service.find(List.of())).isEmpty();
        assertThat(port.calls).as("조회를 한 번도 하지 않아야 한다").isZero();
    }

    @Test
    @DisplayName("지정한 처리의 장면은 활성 처리 제한 없이 같은 판정 규칙으로 접는다")
    void resolvesRunScopedScenes() {
        port.rows = List.of(judgment(31L));

        var resolved = service.resolveForRun(10, 20, List.of(31L));

        assertThat(resolved.get(31L)).singleElement().satisfies(tag -> {
            assertThat(tag.name()).isEqualTo("포항 지진");
            assertThat(tag.verification()).isEqualTo(com.npick.tag.domain.model.EffectiveTag.Verification.UNVERIFIED);
        });
        assertThat(port.runClipId).isEqualTo(10L);
        assertThat(port.runId).isEqualTo(20L);
        assertThat(port.sceneIds).containsExactly(31L);
    }

    @Test
    @DisplayName("태그로 걸린 장면은 번호 오름차순이고 clipId 가 채워진다")
    void sortsMatchedScenesBySceneId() {
        port.rows = List.of(judgment(31L), judgment(30L));

        var matched = service.find(List.of(TagCondition.exact(TagType.EVENT, "포항지진")));

        assertThat(matched).extracting(TagMatchedScene::sceneId).containsExactly(30L, 31L);
        assertThat(matched).allSatisfy(scene -> assertThat(scene.clipId()).isEqualTo(10L));
        assertThat(matched).allSatisfy(scene -> assertThat(scene.matchedTags()).hasSize(1));
    }

    private static TagJudgment judgment(long sceneId) {
        return new TagJudgment(
                sceneId,
                10L,
                7L,
                TagType.EVENT,
                "포항지진",
                "포항 지진",
                false,
                "vlm",
                "unverified",
                Instant.parse("2026-02-01T00:00:00Z"),
                sceneId);
    }

    /** 실제 어댑터와 같은 널 계약을 지킨다. 그래야 「서비스가 null 을 포트까지 흘려보내는가」를 검증할 수 있다. */
    private static final class RecordingPort implements FindTagJudgmentsQueryPort {
        private List<TagJudgment> rows = List.of();
        private int calls;
        private long runClipId;
        private long runId;
        private List<Long> sceneIds = List.of();

        @Override
        public List<TagJudgment> findByScenes(Collection<Long> sceneIds) {
            Objects.requireNonNull(sceneIds, "sceneIds");
            calls++;
            return rows;
        }

        @Override
        public List<TagJudgment> findByConditions(List<TagCondition> conditions) {
            Objects.requireNonNull(conditions, "conditions");
            calls++;
            return rows;
        }

        @Override
        public List<TagJudgment> findByRunScenes(long clipId, long pipelineRunId, Collection<Long> sceneIds) {
            Objects.requireNonNull(sceneIds, "sceneIds");
            calls++;
            runClipId = clipId;
            runId = pipelineRunId;
            this.sceneIds = List.copyOf(sceneIds);
            return rows;
        }
    }
}
