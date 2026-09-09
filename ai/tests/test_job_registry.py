"""단계 레지스트리와 워밍업, 그리고 패키지 배치 규약의 기계 가드.

FRD 단계 이름은 여기서 **전사**한다. `stages.py` 를 import 하면 검증이 자기 자신을
확인하는 셈이 된다(tests/test_health.py 의 같은 이유).
"""

import ast
from pathlib import Path

import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.errors import UpstreamOutputInvalidError
from npick_worker.jobs.registry import (
    HANDLERS,
    StageContext,
    StageHandler,
    capability_versions,
    resolve,
    warm_up,
)

#: FRD 처리 단계 10종. 이름과 순서는 문서에서 옮겨 적는다.
FRD_STAGE_NAMES = (
    "scene_detection",
    "frame_extraction",
    "vlm_metadata",
    "ocr",
    "transcript_selection",
    "asr",
    "scene_transcript_mapping",
    "entity_extraction",
    "text_embedding",
    "indexing",
)

#: 단계가 아닌 패키지. 여기 없는 디렉터리가 생기면 배치 규약이 흔들린 것이다.
#: `jobs` 는 파이프라인 워커의 자리이고 `query_normalization`·`query_resolver` 는
#: 질의 리졸버의 자리다 — 배포 단위 둘(docs/architecture/02-container.md 의 *요소* 표).
NON_STAGE_PACKAGES = {"config", "jobs", "query_normalization", "query_resolver"}

SRC_ROOT = Path(__file__).resolve().parent.parent / "src" / "npick_worker"


def _jobs_import_lines(source: Path) -> list[int]:
    """`npick_worker.jobs` 를 import 하는 줄 번호."""
    tree = ast.parse(source.read_text(encoding="utf-8"))
    lines: list[int] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom):
            if (node.module or "").startswith("npick_worker.jobs"):
                lines.append(node.lineno)
        elif isinstance(node, ast.Import) and any(
            alias.name.startswith("npick_worker.jobs") for alias in node.names
        ):
            lines.append(node.lineno)
    return lines


def _context(
    stage: str,
    video: Path,
    work_dir: Path,
    upstream: dict[str, object] | None = None,
) -> StageContext:
    """배정 하나를 흉내낸 실행 맥락.

    `outputKeyPrefix` 는 계약 §5 의 모양(`runs/{runId}/{stage}/a{attempt}/`)을 그대로
    쓴다. attempt 가 접두에 들어 있어 실패한 시도의 파일이 성공한 시도를 덮어쓸 수
    없다는 성질이 키 조립에 실제로 반영되는지 여기서 함께 확인된다.
    """
    return StageContext(
        stage=stage,
        video_path=video,
        storage_key="clips/1/source.mp4",
        work_dir=work_dir,
        output_key_prefix=f"runs/398021847361024/{stage}/a1/",
        upstream=upstream or {},
    )


# ── 등록 ─────────────────────────────────────────────────────────────


def test_implemented_stages_are_exactly_the_two_earliest() -> None:
    """FRD 단계 표 10개 중 지금 구현된 것만. 나머지 여덟은 resolve() 가 None 이다."""
    assert set(HANDLERS) == {"scene_detection", "frame_extraction"}


def test_every_handler_is_an_frd_stage() -> None:
    # 표에 없는 이름으로 핸들러를 등록하면 BE 가 배정할 수 없는 단계가 생긴다.
    assert set(HANDLERS) <= set(FRD_STAGE_NAMES)


def test_resolve_returns_none_for_unimplemented_stages() -> None:
    assert resolve("ocr") is None
    assert resolve("nope") is None


def test_resolve_returns_the_handler_for_scene_detection() -> None:
    handler = resolve("scene_detection")
    assert handler is not None
    assert handler.name == "scene_detection"


def test_handlers_mapping_is_not_mutable() -> None:
    with pytest.raises(TypeError):
        HANDLERS["ocr"] = HANDLERS["scene_detection"]  # type: ignore[index]


# ── 패키지 배치 (ai/AGENTS.md 의 기계 가드) ─────────────────────────


def test_package_directories_are_stages_or_declared_non_stages() -> None:
    """단계 구현은 단계 이름과 같은 패키지에 둔다는 규약을 기계로 지킨다.

    `jobs` 는 단계가 아니라서 규약과 부딪힐 소지가 있다. 사람이 기억하는 대신
    여기서 걸리게 한다. 새 패키지를 만들면 이 목록을 먼저 고쳐야 한다.
    """
    packages = {
        path.name
        for path in SRC_ROOT.iterdir()
        # 파이썬 파일이 없으면 패키지가 아니다. 캐시만 남은 빈 디렉터리를 세지 않는다.
        if path.is_dir() and not path.name.startswith(("_", ".")) and any(path.glob("*.py"))
    }
    assert packages <= set(FRD_STAGE_NAMES) | NON_STAGE_PACKAGES


def test_stage_packages_do_not_import_the_jobs_layer() -> None:
    """단계는 순수하게 둔다. jobs 를 import 하는 순간 pipeline 배선이 단계 안으로 들어온다."""
    offenders = [
        f"{source.name}:{line}"
        for stage in FRD_STAGE_NAMES
        for source in (SRC_ROOT / stage).rglob("*.py")
        for line in _jobs_import_lines(source)
    ]
    assert offenders == []


# ── 워밍업 ───────────────────────────────────────────────────────────


def test_warm_up_reports_the_scene_detection_identity() -> None:
    report = warm_up(["scene_detection"])
    outcome = report.stages[0]
    assert outcome.warmed is True
    assert "scene-detect/v1:" in outcome.detail
    assert "pyscenedetect" in outcome.detail


def test_warm_up_marks_unimplemented_stages_as_not_warmed() -> None:
    # 없는 것을 있는 척하지 않는다.
    report = warm_up(["ocr"])
    assert report.stages[0].warmed is False
    assert report.ready is False


def test_warm_up_never_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    """워밍업 실패는 워커를 느리게 할 뿐 기동을 막지 않는다(device.py 와 같은 규칙)."""

    def explode() -> str:
        msg = "warm 실패"
        raise RuntimeError(msg)

    broken = StageHandler("scene_detection", HANDLERS["scene_detection"].run, explode)
    monkeypatch.setattr(registry, "HANDLERS", {"scene_detection": broken})

    report = warm_up(["scene_detection"])
    assert report.ready is False
    assert report.stages[0].warmed is False
    assert "RuntimeError" in report.stages[0].detail


def test_warm_up_reports_the_resolved_device() -> None:
    # GPU 가 없어도 워커는 기동한다. device 는 무엇이든 문자열이어야 한다.
    assert warm_up([]).device


# ── claim 에 실을 버전 ───────────────────────────────────────────────


def test_capability_versions_cover_exactly_the_implemented_stages() -> None:
    assert set(capability_versions()) == set(HANDLERS)


def test_capability_version_matches_what_the_run_reports(
    make_video: object, tmp_path: Path
) -> None:
    """선언한 버전과 실제로 만든 버전이 달라지면 BE 의 배정 필터가 무의미해진다."""
    assert callable(make_video)
    video = make_video("capability", [("bars", 20), ("white", 20)])

    declared = capability_versions()["scene_detection"]
    produced = HANDLERS["scene_detection"].run(_context("scene_detection", video, tmp_path))
    assert produced.versions.stage_version == declared


def test_frame_extraction_capability_version_matches_what_the_run_reports(
    make_video: object, tmp_path: Path
) -> None:
    """같은 검사를 2단계에도 한다. 조립 지점이 둘로 갈라졌는지 여기서 잡힌다."""
    assert callable(make_video)
    video = make_video("capability-frames", [("bars", 20), ("white", 20)])

    declared = capability_versions()["frame_extraction"]
    produced = HANDLERS["frame_extraction"].run(
        _context(
            "frame_extraction",
            video,
            tmp_path,
            upstream={
                "sceneDetection": {
                    "scenes": [
                        {"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 2000},
                        {"sceneIndex": 1, "startTimeMs": 2000, "endTimeMs": 4000},
                    ],
                    "mediaDurationMs": 4000,
                    "frameRate": 10.0,
                }
            },
        )
    )
    assert produced.versions.stage_version == declared


def _frame_extraction_upstream(**overrides: object) -> dict[str, object]:
    """2000ms 두 장면짜리 정상 상류 산출물. 어긋남을 하나씩 심어 보기 위한 바탕이다."""
    payload: dict[str, object] = {
        "scenes": [
            {"sceneIndex": 0, "startTimeMs": 0, "endTimeMs": 2000},
            {"sceneIndex": 1, "startTimeMs": 2000, "endTimeMs": 4000},
        ],
        "mediaDurationMs": 4000,
        "frameRate": 10.0,
    }
    payload.update(overrides)
    return {"sceneDetection": payload}


def test_frame_extraction_rejects_an_upstream_frame_rate_that_is_not_the_media(
    make_video: object, tmp_path: Path
) -> None:
    """상류가 쓴 프레임레이트와 이 미디어의 값이 다르면 거절한다.

    그대로 진행하면 실패가 아니라 조용히 틀린 `timestamp_ms` 가 나온다 — 장 수는 줄지
    않으므로 장 수 검사가 걸러 주지 않고, keyframe 의 timestamp 는 검수자가 근거 프레임을
    누르는 좌표다. 계약 §4.3.1 대로 영구 오류다.
    """
    assert callable(make_video)
    video = make_video("upstream-fps", [("bars", 20), ("white", 20)])

    with pytest.raises(ValueError, match="프레임레이트가 상류 산출물과 다르다"):
        HANDLERS["frame_extraction"].run(
            _context(
                "frame_extraction",
                video,
                tmp_path,
                upstream=_frame_extraction_upstream(frameRate=25.0),
            )
        )


def test_frame_extraction_rejects_an_upstream_whose_scenes_do_not_cover_the_duration(
    make_video: object, tmp_path: Path
) -> None:
    """scene 이 덮는 끝과 보고한 길이가 다르면 두 필드가 다른 산출물에서 온 것이다.

    scene_detection 은 마지막 scene 을 영상 끝에서 닫으므로 두 값은 같아야 한다. 필수로
    받는 필드를 쓰지 않으면 이 어긋남이 검증되지 않은 채 남는다.
    """
    assert callable(make_video)
    video = make_video("upstream-duration", [("bars", 20), ("white", 20)])

    with pytest.raises(UpstreamOutputInvalidError, match="보고한 길이가 다르다"):
        HANDLERS["frame_extraction"].run(
            _context(
                "frame_extraction",
                video,
                tmp_path,
                upstream=_frame_extraction_upstream(mediaDurationMs=9000),
            )
        )


def test_handler_without_warm_still_declares_its_version(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """ "워밍업이 없다" 와 "버전을 선언할 수 없다" 는 다른 말이다.

    한데 묶으면 warm=None 인 핸들러가 capabilities 에서 조용히 빠지고, BE 는
    "이 목록에 없는 단계를 배정하지 않는다" 이므로 그 단계는 resolve() 가 돌릴 수
    있어도 영원히 배정되지 않는다. 로그도 남지 않는다.
    """
    cold = StageHandler("scene_detection", HANDLERS["scene_detection"].run, None)
    monkeypatch.setattr(registry, "HANDLERS", {"scene_detection": cold})

    assert "scene_detection" in capability_versions()
