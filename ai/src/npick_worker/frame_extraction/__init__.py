"""FRD §3 F-03 프레임 추출 (`stages.py` 2단계 `frame_extraction`, 치명).

scene 마다 **복수 keyframe** 을 뽑고 그중 **결과 카드에 쓸 대표 이미지 1개** 를 고른다
(`docs/frd.md:121`). 임계값은 전부 버전이 붙은 설정에 있다.

세 가지를 이 모듈이 지킨다.

- **원본 해상도.** 다운스케일하지 않는다. 작은 글자 OCR 이 축소된 대표 이미지가 아니라
  원본 해상도 프레임을 써야 하기 때문이다(`docs/frd.md:131`). 결과 카드용 축소는 이
  단계의 일이 아니다.
- **scene 과의 연결.** 모든 keyframe 은 `scene_index` 를 들고 나간다. 안정 ID(`scene_id`)
  발급은 BE 의 몫이고 워커는 순번으로 말한다(`docs/contracts/job-api.md` §4.3).
- **경로를 만들지 않는다.** 파일명만 정하고 `storage_key` 접두는 잡 레이어가 붙인다.
  이 모듈은 `pipeline_run_id` 도 미디어 루트도 모른다.

이 모듈은 순수 함수만 제공한다. 산출물 업로드와 pipeline run 배선은 `jobs/` 의 몫이다.
"""

from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Final

from npick_worker.frame_extraction.config import (
    DEFAULT_CONFIG_PATH,
    FrameExtractionConfig,
    get_default_config,
    load_config,
)
from npick_worker.frame_extraction.extractor import (
    FrameGrabber,
    MediaProfile,
    SceneRequest,
    WrittenImage,
)
from npick_worker.frame_extraction.models import (
    FILE_NAME_TEMPLATE,
    FrameExtractionResult,
    Keyframe,
    SceneKeyframes,
    SceneSpan,
)
from npick_worker.frame_extraction.pyav_backend import PyAvFrameGrabber
from npick_worker.frame_extraction.selector import (
    ChosenFrame,
    SceneMeasurement,
    ScoredFrame,
    SlotCandidates,
    SlotPlan,
    order_for_output,
    plan_slots,
    prune_by_change,
    select,
)
from npick_worker.timecode import frames_in_range, ms_to_frame

__all__ = [
    "DEFAULT_CONFIG_PATH",
    "FILE_NAME_TEMPLATE",
    "FRAME_RATE_TOLERANCE",
    "ChosenFrame",
    "FrameExtractionConfig",
    "FrameExtractionResult",
    "FrameGrabber",
    "Keyframe",
    "MediaProfile",
    "PyAvFrameGrabber",
    "SceneKeyframes",
    "SceneMeasurement",
    "SceneRequest",
    "SceneSpan",
    "ScoredFrame",
    "SlotCandidates",
    "SlotPlan",
    "WrittenImage",
    "extract_keyframes",
    "frames_in_span",
    "get_default_config",
    "load_config",
    "order_for_output",
    "plan_slots",
    "prune_by_change",
    "select",
]


def extract_keyframes(
    video_path: Path,
    scenes: Sequence[SceneSpan],
    out_dir: Path,
    cfg: FrameExtractionConfig | None = None,
    grabber: FrameGrabber | None = None,
    expected_frame_rate: float | None = None,
) -> FrameExtractionResult:
    """scene 목록을 keyframe 이미지 집합으로 바꾼다.

    재현성 식별자는 `(config_version, engine, engine_version)` 튜플이다. 같은
    `video_path` · 같은 `scenes` · 같은 식별자면 항상 같은 프레임을 고르고 같은 대표를
    낸다. 재처리가 산출물의 의미를 바꾸지 않아야 한다는 요구(FRD §3 F-03 — 재시도가
    성공 산출물을 중복 생성하지 않는다)의 전제다.
    """
    config = cfg if cfg is not None else get_default_config()
    engine = grabber if grabber is not None else PyAvFrameGrabber()

    _validate_scenes(scenes)
    profile = engine.profile(video_path)
    if expected_frame_rate is not None:
        _check_frame_rate(profile.frame_rate, expected_frame_rate)

    spans = {scene.scene_index: frames_in_span(scene, profile.frame_rate) for scene in scenes}
    requests = tuple(
        SceneRequest(
            scene_index=scene.scene_index,
            slots=tuple(
                _to_frames(plan, spans[scene.scene_index], profile.frame_rate)
                for plan in plan_slots(scene, config, profile.frame_rate)
            ),
        )
        for scene in scenes
    )

    empty = SceneMeasurement(frames={}, changes={})
    measured = engine.measure(video_path, requests, config)
    _check_measured(requests, measured)
    ordered = {
        request.scene_index: order_for_output(
            _choose(request.slots, measured.get(request.scene_index, empty), config)
        )
        for request in requests
    }
    for scene in scenes:
        _check_keyframe_count(scene, ordered[scene.scene_index], spans[scene.scene_index], config)

    written = engine.write(video_path, _targets(scenes, ordered, out_dir), config)

    return FrameExtractionResult(
        scenes=tuple(
            SceneKeyframes(
                scene_index=scene.scene_index,
                keyframes=tuple(
                    _to_keyframe(scene.scene_index, frame, written)
                    for frame in ordered[scene.scene_index]
                ),
            )
            for scene in scenes
        ),
        config_version=config.version_id,
        engine=engine.name,
        engine_version=engine.version,
        frame_rate=profile.frame_rate,
        image_width=profile.width,
        image_height=profile.height,
    )


def _check_measured(
    requests: Sequence[SceneRequest],
    measured: Mapping[int, SceneMeasurement],
) -> None:
    """**자리 하나가 통째로** 미디어 밖이면 멈춘다.

    디코드는 순차로 훑으므로(`pyav_backend._decode_until`) 요청한 프레임에 닿지 못하는
    경우는 둘이다. **미디어가 그 전에 끝났거나**(상류 scene 목록이 이 미디어의 것이
    아니다 — 길이가 다른 파일, 잘린 입력), **그 시각에 프레임이 없거나.** 뒤엣것은
    프레임 번호가 PTS 에서 오기 때문에 생긴다(`timecode.frame_number_from_pts`) —
    간격이 고르지 않으면 번호가 건너뛰고, 건너뛴 번호에는 디코드할 프레임이 없다.

    둘을 메시지에서 가른다. 원인이 다르면 고칠 곳도 다른데, 영구 오류라 정본에 남는
    원인이 그대로 조사의 출발점이 된다.

    이 사실을 장 수로 추론하지 않는 이유가 있다. 예전에는 `_check_keyframe_count` 가
    "하한보다 적다" 로 함께 걸러 줬는데, 그건 자리를 2 개만 놓던 시절에 그중 하나가
    미디어 밖이라 성립한 우연이었다. 자리 수가 늘면 앞쪽 자리만으로 하한이 채워져 같은
    입력이 조용히 성공한다 — **같은 사실이 자리 수에 따라 실패와 성공으로 갈린다.**
    측정이 닿았는지를 직접 보면 자리 수와 무관하게 같은 답이 나온다.

    **후보 하나가 아니라 자리 하나를 본다.** 막으려는 것은 자리가 조용히 사라져 장 수가
    주는 일이고, `select` 는 자리 안의 다른 후보로 그 자리를 채울 수 있다. 후보 단위로
    보면 그렇게 채워지는 경우까지 clip 전체를 영구 실패시킨다 — 자리를 2 개에서
    5 개로 늘리면서 마지막 자리가 창의 75% 에서 90% 로 옮겨 갔기 때문에, 컨테이너가
    선언한 길이가 실제 디코드 가능 구간보다 긴 파일(TS, 잘린 꼬리, 추정 duration)에서
    닿을 확률이 그만큼 커졌다. 자리 단위로 보면 **막으려던 것은 그대로 막고**(자리가
    사라지면 실패) 대체 가능한 초과분은 통과한다.

    다시 시도해도 같은 결과이므로 `ValueError` 이고 잡 레이어가 영구 오류로 번역한다.
    """
    for request in requests:
        measurement = measured.get(request.scene_index)
        reached = measurement.frames if measurement is not None else {}
        for slot in request.slots:
            if any(frame_number in reached for frame_number in slot.frame_numbers):
                continue
            wanted = sorted(slot.frame_numbers)
            where = (
                f"scene_index={request.scene_index} "
                f"(자리 {slot.slot_index} 의 후보 {wanted} 에 닿지 못했다)"
            )
            if reached and max(reached) > wanted[-1]:
                # 이 scene 안에서 더 뒤의 프레임을 쟀다. 미디어는 거기까지 있었으므로
                # 못 닿은 이유는 길이가 아니라 번호가 건너뛴 것이다.
                msg = f"그 시각에 프레임이 없다: {where}"
            else:
                msg = f"미디어가 scene 보다 먼저 끝났다: {where}"
            raise ValueError(msg)


def _choose(
    slots: Sequence[SlotCandidates],
    measurement: SceneMeasurement,
    config: FrameExtractionConfig,
) -> tuple[ChosenFrame, ...]:
    """자리마다 선명도로 한 장을 고른 뒤, 서로 충분히 다른 것만 남긴다.

    순서가 중요하다. 선명도가 **무엇을** 저장할지 정하고 변화량이 **몇 장을** 남길지
    정한다. 두 기준이 최적화하는 대상이 다르기 때문에 한 단계로 합치지 않는다 —
    변화량은 장 수·위치를 내용으로 정하고(FRD v3.2 F-03), 선명도는 그 자리에서 뽑는
    프레임의 품질을 보장한다.
    """
    return prune_by_change(select(slots, measurement.frames, config), measurement, config)


def _validate_scenes(scenes: Sequence[SceneSpan]) -> None:
    """입력 scene 목록의 불변식.

    상류 산출물이 BE 를 거쳐 JSON 으로 오므로 이 단계에서 다시 확인한다. 겹치는 구간을
    그대로 받으면 같은 프레임이 두 scene 의 keyframe 이 되고, `ocr_observation` 이
    어느 장면의 근거인지가 흐려진다.
    """
    if not scenes:
        msg = "scene 이 없다. 상류 scene detection 산출물이 비어 있다"
        raise ValueError(msg)

    indexes = [scene.scene_index for scene in scenes]
    if len(set(indexes)) != len(indexes):
        msg = f"scene_index 가 중복됐다: {sorted(indexes)}"
        raise ValueError(msg)

    previous: SceneSpan | None = None
    for scene in scenes:
        if scene.start_time_ms < 0 or scene.duration_ms <= 0:
            msg = (
                f"유효하지 않은 scene 구간이다: scene_index={scene.scene_index} "
                f"({scene.start_time_ms}~{scene.end_time_ms}ms)"
            )
            raise ValueError(msg)
        if previous is not None and scene.start_time_ms < previous.end_time_ms:
            # 오름차순이 아니거나 겹친다. 순차 디코드 한 번으로 처리하려면 시간순이
            # 전제이므로, 정렬해서 넘기지 않고 거절한다 — 겹침은 정렬로 고쳐지지 않고
            # 상류가 잘못됐다는 신호다.
            msg = (
                f"scene 이 시간순이 아니거나 겹친다: "
                f"scene_index={previous.scene_index}({previous.end_time_ms}ms) 다음에 "
                f"scene_index={scene.scene_index}({scene.start_time_ms}ms)"
            )
            raise ValueError(msg)
        previous = scene


#: 프레임레이트 대조 허용 오차. 정상 경로에서는 상류와 이 단계가 같은 `guessed_rate` 를
#: 읽으므로 차가 0 이다. JSON 왕복에서 생길 수 있는 마지막 자리 차이만 허용한다.
FRAME_RATE_TOLERANCE: Final[float] = 0.01


def _check_frame_rate(actual: float, expected: float) -> None:
    """이 미디어의 프레임레이트가 상류가 쓴 값과 같은지 본다.

    ms 를 프레임 번호로 되돌리는 데 쓰는 값이 상류가 scene 경계를 만들 때 쓴 값과 다르면
    실패가 아니라 **조용히 틀린 `timestamp_ms`** 가 나온다. 장 수는 줄지 않으므로
    `_check_keyframe_count` 가 걸러 주지도 않고, keyframe 의 timestamp 는 검수자가 근거
    프레임을 누르는 좌표라 조용한 오차가 가장 나쁘다.

    상류 산출물이 이 미디어의 것이 아니라는 신호이므로 다시 시도해도 같다 — `ValueError`
    이고 잡 레이어가 영구 오류로 번역한다.
    """
    if abs(actual - expected) <= FRAME_RATE_TOLERANCE:
        return
    msg = f"미디어의 프레임레이트가 상류 산출물과 다르다: {actual:g}fps (상류 {expected:g}fps)"
    raise ValueError(msg)


def frames_in_span(scene: SceneSpan, frame_rate: float) -> tuple[int, int]:
    """scene 구간 안에 정규 시각이 들어오는 프레임 번호의 폐구간 `[처음, 마지막]`.

    세는 규칙은 `timecode.frames_in_range` 다. 여기 있는 것은 **빈 구간을 실패로 번역하는
    일**뿐이다 — 그 구간이 scene 이라는 것을 아는 쪽이 여기이고, 그래서 오류 메시지에
    `scene_index` 를 담을 수 있다.
    """
    span = frames_in_range(scene.start_time_ms, scene.end_time_ms, frame_rate)
    if span is None:
        # 구간이 한 프레임 간격보다 짧아 정규 시각이 들어오는 프레임이 없다.
        # scene_detection 은 `min_scene_len_ms` 로 이런 구간을 만들지 않지만, 상류가
        # 무엇이든 여기서 조용히 빈 결과를 내지는 않는다.
        msg = (
            f"scene 구간 안에 프레임이 없다: scene_index={scene.scene_index} "
            f"({scene.start_time_ms}~{scene.end_time_ms}ms, {frame_rate:g}fps)"
        )
        raise ValueError(msg)
    return span


def _to_frames(plan: SlotPlan, span: tuple[int, int], frame_rate: float) -> SlotCandidates:
    """슬롯의 목표 시각을 프레임 번호로 옮긴다. 선호 순서를 유지하며 중복을 없앤다.

    `span` 밖으로 나간 후보는 구간 안으로 당긴다. 그 결과 다른 후보와 같은 프레임이
    되면 중복을 없애므로, 짧은 scene 에서는 후보가 목표 시각보다 적을 수 있다 — 같은
    프레임을 두 번 재는 것은 비용만 들고 아무것도 바꾸지 않는다.
    """
    first, last = span
    frames: dict[int, None] = {}
    for candidate_ms in plan.candidates_ms:
        frames.setdefault(min(max(ms_to_frame(candidate_ms, frame_rate), first), last))
    return SlotCandidates(slot_index=plan.slot_index, frame_numbers=tuple(frames))


def _check_keyframe_count(
    scene: SceneSpan,
    chosen: Sequence[ChosenFrame],
    span: tuple[int, int],
    config: FrameExtractionConfig,
) -> None:
    """그 scene 에서 뽑을 수 있는 만큼 뽑았는지 본다. 아니면 멈춘다.

    `prune_by_change` 가 하한을 보장하므로 변화량 때문에 여기 걸리는 일은 없다. 남는 것은
    **선정 단계에서 자리가 사라지는** 경우다 — `select` 는 이미 다른 자리가 고른 프레임을
    다시 고르지 않으므로, 창이 좁아 자리들의 후보가 한 프레임으로 몰리면 자리가 버려진다.
    부족한 채 성공하면 BE 는 그 장면의 keyframe 이 원래 그만큼인 줄 안다.

    미디어가 scene 보다 먼저 끝나는 경우는 여기가 아니라 `_check_measured` 가 잡는다. 그
    사실을 장 수로 추론하면 자리 수에 따라 실패와 성공으로 갈리기 때문이다.

    기대치에 `min` 을 쓰는 이유는 구간에 프레임이 한 장뿐인 scene 도 있기 때문이다. 그때
    1 장은 결함이 아니라 그 구간의 전부다 — 없는 프레임을 요구하지 않는다.

    다시 시도해도 같은 결과이므로 `ValueError` 다. 잡 레이어가 영구 오류로 번역한다.
    """
    first, last = span
    expected = min(config.min_keyframes_per_scene, last - first + 1)
    if len(chosen) >= expected:
        return
    where = f"scene_index={scene.scene_index} ({scene.start_time_ms}~{scene.end_time_ms}ms)"
    if not chosen:
        msg = f"scene 구간에서 프레임을 얻지 못했다: {where}"
    else:
        msg = f"scene 의 keyframe 이 기대보다 적다: {where} (기대 {expected}, 실제 {len(chosen)})"
    raise ValueError(msg)


def _targets(
    scenes: Sequence[SceneSpan],
    ordered: Mapping[int, Sequence[ChosenFrame]],
    out_dir: Path,
) -> dict[int, Path]:
    """`{프레임 번호: 저장 경로}`.

    파일명에 `timestamp_ms` 를 넣는다. 프레임 번호가 아닌 이유는 이 이름이 그대로
    `keyframe.storage_key` 의 뒷부분이 되고, DB 에 남는 식별자는 `timestamp_ms` 라서
    파일과 행을 사람이 대조할 수 있어야 하기 때문이다.
    """
    return {
        frame.frame_number: out_dir / _file_name(scene.scene_index, frame.timestamp_ms)
        for scene in scenes
        for frame in ordered[scene.scene_index]
    }


def _file_name(scene_index: int, timestamp_ms: int) -> str:
    return FILE_NAME_TEMPLATE.format(scene_index=scene_index, timestamp_ms=timestamp_ms)


def _to_keyframe(
    scene_index: int, frame: ChosenFrame, written: Mapping[int, WrittenImage]
) -> Keyframe:
    image = written.get(frame.frame_number)
    if image is None:
        # measure 가 닿은 프레임에 write 가 닿지 못했다. 같은 파일을 같은 방식으로 두 번
        # 디코드했는데 결과가 다르다는 뜻이므로 결정론 전제가 깨진 것이다. 그 상태로
        # 반쯤 채운 결과를 내면 BE 는 keyframe 이 원래 그만큼인 줄 안다.
        msg = f"고른 프레임을 저장하지 못했다: scene_index={scene_index} frame={frame.frame_number}"
        raise ValueError(msg)
    return Keyframe(
        scene_index=scene_index,
        timestamp_ms=frame.timestamp_ms,
        frame_number=frame.frame_number,
        file_name=_file_name(scene_index, frame.timestamp_ms),
        byte_size=image.byte_size,
        content_sha256=image.content_sha256,
        score=frame.score,
        blank=frame.blank,
    )
