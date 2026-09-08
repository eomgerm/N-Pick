"""단계 레지스트리와 워밍업, 그리고 패키지 배치 규약의 기계 가드.

FRD 단계 이름은 여기서 **전사**한다. `stages.py` 를 import 하면 검증이 자기 자신을
확인하는 셈이 된다(tests/test_health.py 의 같은 이유).
"""

import ast
from pathlib import Path

import pytest

from npick_worker.jobs import registry
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
#: `jobs` 와 `query_resolver` 는 배포 단위 둘의 자리다 — 각각 파이프라인 워커와
#: 질의 리졸버(docs/architecture/02-container.md 의 *요소* 표).
NON_STAGE_PACKAGES = {"config", "jobs", "query_resolver"}

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


# ── 등록 ─────────────────────────────────────────────────────────────


def test_only_scene_detection_is_implemented() -> None:
    assert set(HANDLERS) == {"scene_detection"}


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


def test_capability_version_matches_what_the_run_reports(make_video: object) -> None:
    """선언한 버전과 실제로 만든 버전이 달라지면 BE 의 배정 필터가 무의미해진다."""
    assert callable(make_video)
    video = make_video("capability", [("bars", 20), ("white", 20)])

    declared = capability_versions()["scene_detection"]
    produced = HANDLERS["scene_detection"].run(
        StageContext(stage="scene_detection", video_path=video, storage_key="k")
    )
    assert produced.versions.stage_version == declared
