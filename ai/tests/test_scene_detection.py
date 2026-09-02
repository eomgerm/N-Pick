"""FRD §5.3 scene detection 검증.

기대값은 코드가 아니라 FRD 와 config toml 에서 옮겨 적는다(tests/test_health.py 규약).
구현 모듈을 참조해 기대값을 만들면 검증이 자기 자신을 확인하는 셈이 된다.
"""

from collections.abc import Callable, Sequence
from importlib import metadata
from pathlib import Path

import pytest

from npick_worker.scene_detection import (
    DEFAULT_CONFIG_PATH,
    RawDetection,
    SceneDetectionConfig,
    detect_scenes,
    frames_to_ms,
    load_config,
)

MakeVideo = Callable[[str, Sequence[tuple[str, int]]], Path]

#: conftest 의 합성 영상은 10fps 다. 20프레임 = 2000ms.
BLOCK_FRAMES = 20
BLOCK_MS = 2000


class FakeDetector:
    """주입 경로가 구현 provenance 와 원시 경계를 보존하는지 확인하는 대역."""

    name = "fake-detector"
    version = "test-1.2.3"

    def detect(self, video_path: Path, cfg: SceneDetectionConfig) -> RawDetection:
        return RawDetection(boundaries_ms=(0, 2000, 5000), duration_ms=8000, frame_rate=30.0)


def _cfg(**overrides: object) -> SceneDetectionConfig:
    """기본 설정에서 일부만 바꾼 사본. 테스트가 toml 을 건드리지 않게 한다."""
    return load_config().model_copy(update=overrides)


# ── FR-PRC-010: [start,end) 구간 분할 ──────────────────────────────────


def test_frames_to_ms_rounds_to_nearest_integer() -> None:
    assert frames_to_ms(2, 30.0) == 67
    assert frames_to_ms(20, 10.0) == 2000


def test_three_shot_video_splits_into_three_scenes(make_video: MakeVideo) -> None:
    video = make_video(
        "three", [("bars", BLOCK_FRAMES), ("white", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)]
    )

    result = detect_scenes(video)

    assert [(s.start_time_ms, s.end_time_ms) for s in result.scenes] == [
        (0, BLOCK_MS),
        (BLOCK_MS, BLOCK_MS * 2),
        (BLOCK_MS * 2, BLOCK_MS * 3),
    ]
    assert [s.scene_index for s in result.scenes] == [0, 1, 2]


def test_single_shot_video_yields_exactly_one_scene(make_video: MakeVideo) -> None:
    """FR-PRC-010 의 하한. 컷이 없어도 scene 은 한 개 이상이어야 한다."""
    video = make_video("single", [("gray", BLOCK_FRAMES * 2)])

    result = detect_scenes(video)

    assert len(result.scenes) == 1
    assert result.scenes[0].start_time_ms == 0
    assert result.scenes[0].end_time_ms == result.duration_ms


@pytest.mark.parametrize(
    "blocks",
    [
        [("bars", BLOCK_FRAMES), ("white", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)],
        [("gray", BLOCK_FRAMES * 2)],
        [("noise", BLOCK_FRAMES), ("bars", BLOCK_FRAMES)],
    ],
)
def test_scenes_are_contiguous_half_open_intervals(
    make_video: MakeVideo, blocks: Sequence[tuple[str, int]]
) -> None:
    """FRD §15.6: scene interval 은 [start,end). 틈도 겹침도 없어야 한다."""
    result = detect_scenes(make_video("contiguous", blocks))

    assert result.scenes[0].start_time_ms == 0
    assert result.scenes[-1].end_time_ms == result.duration_ms
    for earlier, later in zip(result.scenes, result.scenes[1:], strict=False):
        assert earlier.end_time_ms == later.start_time_ms
        assert earlier.start_time_ms < earlier.end_time_ms


# ── FR-PRC-006: 재시도 멱등성 ──────────────────────────────────────────


def test_same_input_and_config_produce_identical_result(make_video: MakeVideo) -> None:
    video = make_video("idem", [("bars", BLOCK_FRAMES), ("noise", BLOCK_FRAMES)])

    assert detect_scenes(video) == detect_scenes(video)


# ── FR-PRC-015: 임계값·최소 길이가 설정과 version 에 묶여 있는가 ────────


def test_min_scene_len_absorbs_short_flash(make_video: MakeVideo) -> None:
    """3프레임(300ms) 플래시가 min_scene_len_ms 로 흡수된다."""
    blocks = [("bars", BLOCK_FRAMES), ("white", 3), ("bars", BLOCK_FRAMES)]
    video = make_video("flash", blocks)

    without_guard = detect_scenes(video, _cfg(min_scene_len_ms=0))
    with_guard = detect_scenes(video, _cfg(min_scene_len_ms=1000))

    assert len(without_guard.scenes) > len(with_guard.scenes)
    assert all(s.duration_ms >= 1000 for s in with_guard.scenes)


def test_threshold_change_changes_scene_count(make_video: MakeVideo) -> None:
    """임계값이 실제로 분할에 영향을 준다 — config 가 장식이 아님을 확인한다."""
    video = make_video("threshold", [("bars", BLOCK_FRAMES), ("white", BLOCK_FRAMES)])
    base = load_config()

    sensitive = base.model_copy(
        update={"content": base.content.model_copy(update={"threshold": 5.0})}
    )
    insensitive = base.model_copy(
        update={"content": base.content.model_copy(update={"threshold": 200.0})}
    )

    assert len(detect_scenes(video, sensitive).scenes) == 2
    assert len(detect_scenes(video, insensitive).scenes) == 1


def test_version_id_is_stable_and_sensitive() -> None:
    base = load_config()
    changed = base.model_copy(update={"min_scene_len_ms": base.min_scene_len_ms + 1})

    assert base.version_id == load_config().version_id
    assert base.version_id != changed.version_id
    assert base.version_id.startswith("scene-detect/v1:")


def test_result_carries_config_version(make_video: MakeVideo) -> None:
    video = make_video("version", [("bars", BLOCK_FRAMES)])

    assert detect_scenes(video).config_version == load_config().version_id


def test_result_carries_engine_version(make_video: MakeVideo) -> None:
    """재현성 식별자의 엔진 필드. `config_version` 만으로는 부족하다.

    설정을 그대로 두고 라이브러리만 올려도 경계가 달라질 수 있다. 실제로 이 기능은
    scenedetect 0.6.7.1 → 0.7.1 업그레이드에서 필요해졌다. 버전을 하드코딩하지 않고
    설치된 배포판에서 읽는지 확인한다 (`importlib.metadata` 로 독립 조회해 대조).
    """
    installed = metadata.version("scenedetect-headless")
    video = make_video("engine", [("bars", BLOCK_FRAMES)])

    result = detect_scenes(video)

    assert result.engine == "pyscenedetect"
    assert result.engine_version == installed


def test_injected_detector_controls_provenance_and_boundaries(tmp_path: Path) -> None:
    config = _cfg(detector="adaptive", min_scene_len_ms=1000)

    result = detect_scenes(tmp_path / "not-opened.mp4", config, FakeDetector())

    assert result.engine == "fake-detector"
    assert result.engine_version == "test-1.2.3"
    assert result.detector == "adaptive"
    assert result.config_version == config.version_id
    assert [(scene.start_time_ms, scene.end_time_ms) for scene in result.scenes] == [
        (0, 2000),
        (2000, 5000),
        (5000, 8000),
    ]


# ── Gate B: 임계값이 코드가 아니라 설정에 있는가 ────────────────────────


def test_bundled_config_matches_expected_keys() -> None:
    """toml 키를 리터럴로 적는다. 모델에서 가져오면 드리프트를 못 잡는다."""
    config = load_config()

    assert DEFAULT_CONFIG_PATH.is_file()
    dumped = config.model_dump(by_alias=True)
    assert set(dumped) == {
        "schema",
        "detector",
        "min_scene_len_ms",
        "downscale",
        "frame_skip",
        "content",
        "adaptive",
    }
    assert set(dumped["content"]) == {"threshold", "luma_only"}
    assert set(dumped["adaptive"]) == {"adaptive_threshold", "min_content_val", "window_width"}


def test_config_rejects_unknown_key(tmp_path: Path) -> None:
    """오타난 키가 조용히 무시되면 'version 은 바뀌었는데 동작은 같은' 상황이 된다."""
    broken = tmp_path / "broken.toml"
    broken.write_text(
        DEFAULT_CONFIG_PATH.read_text(encoding="utf-8") + "\nthreshhold = 1.0\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="threshhold"):
        load_config(broken)


def test_no_threshold_literals_in_source() -> None:
    """임계값은 toml 에만 있어야 한다(Gate B, FRD §15.4)."""
    package = DEFAULT_CONFIG_PATH.parent.parent / "scene_detection"
    sources = "\n".join(p.read_text(encoding="utf-8") for p in sorted(package.glob("*.py")))

    for literal in ("27.0", "3.0", "15.0"):
        assert literal not in sources, f"임계값 {literal} 이 코드에 남아 있다"
