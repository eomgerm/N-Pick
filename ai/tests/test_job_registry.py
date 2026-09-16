"""단계 레지스트리와 워밍업, 그리고 패키지 배치 규약의 기계 가드.

FRD 단계 이름은 여기서 **전사**한다. `stages.py` 를 import 하면 검증이 자기 자신을
확인하는 셈이 된다(tests/test_health.py 의 같은 이유).
"""

import ast
import hashlib
import json
import logging
from pathlib import Path

import pytest

from npick_worker.jobs import registry
from npick_worker.jobs.errors import (
    ExternalProcessingRefusedError,
    ModelUnavailableError,
    UpstreamOutputInvalidError,
    VlmOutputInvalidError,
    classify,
)
from npick_worker.jobs.registry import (
    HANDLERS,
    StageContext,
    StageHandler,
    capability_versions,
    resolve,
    warm_up,
)
from npick_worker.settings import get_settings

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
NON_STAGE_PACKAGES = {
    "config",
    "jobs",
    # 검색 시점 모듈 셋. 리졸버 배포 단위의 것이라 `stages.py` 에 없다.
    "query_embedding",
    "query_normalization",
    "query_resolver",
}

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


def test_implemented_stages_are_exactly_the_seven_present() -> None:
    """FRD 단계 표 10개 중 지금 구현된 것만. 나머지 셋은 resolve() 가 None 이다."""
    assert set(HANDLERS) == {
        "scene_detection",
        "frame_extraction",
        "vlm_metadata",
        "ocr",
        "asr",
        "text_embedding",
        "indexing",
    }


def test_every_handler_is_an_frd_stage() -> None:
    # 표에 없는 이름으로 핸들러를 등록하면 BE 가 배정할 수 없는 단계가 생긴다.
    assert set(HANDLERS) <= set(FRD_STAGE_NAMES)


def test_resolve_returns_none_for_unimplemented_stages() -> None:
    assert resolve("transcript_selection") is None
    assert resolve("nope") is None


def test_resolve_returns_the_handler_for_scene_detection() -> None:
    handler = resolve("scene_detection")
    assert handler is not None
    assert handler.name == "scene_detection"


def test_handlers_mapping_is_not_mutable() -> None:
    with pytest.raises(TypeError):
        HANDLERS["asr"] = HANDLERS["scene_detection"]  # type: ignore[index]


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
    report = warm_up(["transcript_selection"])
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


def test_capability_versions_cover_the_stages_this_worker_can_actually_run() -> None:
    """선언은 구현의 부분집합이다. **같지 않을 수 있다.**

    `vlm_metadata` 는 가중치 이름이 설정돼 있어야 버전을 선언할 수 있다
    (`NPICK_AI_VLM_MODEL`, FRD §11 이 모델명을 실측 후 확정으로 둔다). 모델이 없는
    워커가 그 단계를 선언하면 BE 가 배정하고 매번 `MODEL_UNAVAILABLE` 로 죽는다 —
    배정받지 않는 편이 낫다. 테스트 환경에는 모델이 없으므로 빠지는 것이 정상이다.
    """
    declared = set(capability_versions())
    assert declared <= set(HANDLERS)
    assert {"scene_detection", "frame_extraction", "ocr"} <= declared


def test_vlm_is_not_declared_without_a_model(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("NPICK_AI_VLM_MODEL", "")
    get_settings.cache_clear()
    try:
        assert "vlm_metadata" not in capability_versions()
    finally:
        get_settings.cache_clear()


def test_asr_is_not_declared_without_a_model(monkeypatch: pytest.MonkeyPatch) -> None:
    """`vlm_metadata` 와 같은 이유다.

    모델명은 실측 후 확정 대상이라 기본값이 없다(FRD §11). 고르지 않은 워커가 이 단계를
    선언하면 BE 가 배정하고 매번 `MODEL_UNAVAILABLE` 로 죽는다. `gpu` 그룹을 설치하지
    않은 환경도 같은 경로로 빠진다 — 이때는 `NO_ADAPTER` 다.
    """
    monkeypatch.setenv("NPICK_AI_ASR_MODEL", "")
    get_settings.cache_clear()
    try:
        assert "asr" not in capability_versions()
    finally:
        get_settings.cache_clear()


def test_capability_versions_do_not_build_an_ocr_engine_per_poll(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """이 함수는 claim long-poll 한 바퀴마다, 그리고 실패마다 불린다.

    `runner._claim_request` 와 `runner._failure_versions` 가 호출부다. 여기서 엔진을
    새로 만들면 ONNX 세션 생성 비용을 폴링 주기로 내고, 동기 호출이라 이벤트 루프가
    그동안 멈춘다 — OCR 잡을 한 건도 받지 않는 워커도 그 비용을 영구히 낸다.
    선언에 쓰는 값(`name` 은 상수, `version` 은 importlib.metadata)은 인스턴스와
    무관하므로 두 번째 호출부터는 만들 이유가 없다.
    """
    from npick_worker.ocr import rapidocr_backend

    built = [0]

    class _Counted:
        name = "rapidocr"
        version = "counted"

        def __init__(self, config: object | None = None) -> None:
            built[0] += 1

    monkeypatch.setattr(rapidocr_backend, "RapidOcrEngine", _Counted)
    rapidocr_backend.shared_engine.cache_clear()
    try:
        assert capability_versions()["ocr"] == capability_versions()["ocr"]
        assert built[0] == 1
    finally:
        rapidocr_backend.shared_engine.cache_clear()


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


# ── 선언 단계 제한 노브 (S15P21A501-186) ────────────────────────────
# 설치 구성이 정하는 것은 **할 수 있는 것**이고 이 노브가 정하는 것은 **맡을 것**이다.
# CPU 단계 구현은 기본 의존성이라 어느 이미지에나 들어간다. 그래서 GPU 파드도
# scene_detection·ocr 를 선언하고, 두 워커의 목록이 겹치면 무엇을 누가 가져갈지
# 정해지지 않는다. 이 노브가 그것을 가른다.


def _with_job_stages(monkeypatch: pytest.MonkeyPatch, value: str) -> None:
    monkeypatch.setenv("NPICK_AI_JOB_STAGES", value)
    get_settings.cache_clear()
    # 선언 목록 캐시는 설정에서 파생된 값이다. 설정만 비우면 이 헬퍼를 한 테스트 안에서
    # 두 번째로 부를 때 앞 목록을 그대로 본다.
    registry.declared_stages.cache_clear()


def test_job_stages_narrows_the_declaration(monkeypatch: pytest.MonkeyPatch) -> None:
    """구현이 있어도 목록 밖이면 선언하지 않는다. 사람이 넣는 값이라 공백도 받는다."""
    _with_job_stages(monkeypatch, "scene_detection, frame_extraction")

    assert set(capability_versions()) == {"scene_detection", "frame_extraction"}


def test_empty_job_stages_declares_every_implemented_stage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """비어 있으면 지금과 같다. 이 노브는 기본 동작을 바꾸지 않는다."""
    _with_job_stages(monkeypatch, "")

    assert {"scene_detection", "frame_extraction", "ocr"} <= set(capability_versions())


def test_an_unknown_stage_name_is_warned_and_ignored(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """오타 하나로 능력이 통째로 비면 그 워커는 아무 일도 받지 못한다.

    모르는 이름만 버리고 나머지는 그대로 선언한다. 대신 경고를 남긴다 — 버린 사실이
    조용하면 오타는 "왜 이 단계가 배정되지 않는가" 로만 드러난다.
    """
    _with_job_stages(monkeypatch, "scene_detection,scene_detektion")

    with caplog.at_level(logging.WARNING, logger="npick_worker.jobs.registry"):
        declared = capability_versions()

    assert set(declared) == {"scene_detection"}
    assert "scene_detektion" in caplog.text


def test_a_list_of_only_unknown_names_declares_nothing(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """**노브는 좁히기만 한다.** 전부 모르는 이름이라고 전체 선언으로 되돌아가면
    CPU 워커가 GPU 단계를 도로 물고, 그 사실이 "빈 목록" 보다 조용히 묻힌다.
    """
    _with_job_stages(monkeypatch, "장면분할")

    with caplog.at_level(logging.WARNING, logger="npick_worker.jobs.registry"):
        assert capability_versions() == {}

    assert "장면분할" in caplog.text


def test_the_unknown_name_warning_does_not_repeat_per_poll(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    """`capability_versions()` 는 claim 한 바퀴마다 불린다. 여기서 매번 경고하면
    기동 시 한 번 보고 고칠 오타가 폴링 주기로 로그를 채운다.
    """
    _with_job_stages(monkeypatch, "scene_detection,오타")

    with caplog.at_level(logging.WARNING, logger="npick_worker.jobs.registry"):
        capability_versions()
        capability_versions()

    assert caplog.text.count("오타") == 1


def test_warm_up_skips_the_stages_this_deployment_will_not_declare(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """선언하지 않을 단계의 가중치를 기동 때 올릴 이유가 없다. CPU 워커가 asr·vlm
    가중치를 내려받는 것이 이 노브가 겨냥하는 낭비 그 자체다.
    """
    _with_job_stages(monkeypatch, "scene_detection")

    assert [outcome.stage for outcome in warm_up().stages] == ["scene_detection"]


def test_a_worker_that_declares_nothing_is_not_ready(monkeypatch: pytest.MonkeyPatch) -> None:
    """맡을 단계가 하나도 없는 워커를 `ready` 로 보고하면 그 사실이 감춰진다.

    빈 목록에서 `all()` 은 공허하게 참이다. 그대로 두면 오타로 목록이 통째로 날아간
    워커가 `status: ok` · `warmup.ready: true` · `polling.running: true` 를 전부
    통과시키고, 밖에서 보이는 단서는 기동 로그 한 줄뿐이다. 잡은 하나도 안 가져간다.
    """
    _with_job_stages(monkeypatch, "장면분할")

    report = warm_up()

    assert report.stages == ()
    assert report.ready is False


def test_a_stage_without_its_model_stays_out_even_when_the_knob_names_it(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """**노브는 좁히기만 한다.** 목록에 넣는 것으로 선언이 생기지 않는다.

    모델명은 실측 후 확정 대상이라 기본값이 없다(FRD §11). 이 규칙이 노브보다 먼저다 —
    아니면 `NPICK_AI_JOB_STAGES=asr` 한 줄로 모델 없는 워커가 asr 을 배정받고 매번
    `MODEL_UNAVAILABLE` 로 죽는다.
    """
    monkeypatch.setenv("NPICK_AI_ASR_MODEL", "")
    _with_job_stages(monkeypatch, "asr")

    assert capability_versions() == {}


# ── ocr 의 상류 입력 선언 ───────────────────────────────────────────


def _ocr_upstream(*keyframes: tuple[int, int]) -> dict[str, object]:
    """`(sceneIndex, timestampMs)` 목록을 상류 산출물 모양으로 만든다."""
    scenes: dict[int, list[dict[str, object]]] = {}
    for scene_index, timestamp_ms in keyframes:
        scenes.setdefault(scene_index, []).append(
            {
                "sceneIndex": scene_index,
                "timestampMs": timestamp_ms,
                "storageKey": (
                    f"runs/1/frame_extraction/a1/s{scene_index:04d}/kf-{timestamp_ms:09d}.jpg"
                ),
            }
        )
    return {
        "frameExtraction": {
            "scenes": [
                {"sceneIndex": index, "keyframes": items} for index, items in scenes.items()
            ],
            "imageWidth": 1920,
            "imageHeight": 1080,
        }
    }


def test_keyframe_reading_stages_skip_the_source_video() -> None:
    """`needs_video` 는 러너가 원본 영상을 받을지를 정한다.

    `ocr` 과 `vlm_metadata` 는 상류 keyframe 만 보므로 False 다. 나머지는 영상을 열어야
    하고, 거기서 False 가 되면 `require_video()` 가 실행 중에 터진다. `asr` 이 True 인
    이유는 앞의 둘과 다르다 — 보는 것이 화면이 아니라 **원본 파일 안의 오디오**다.
    `text_embedding` 과 `indexing` 은 이미지조차 열지 않는다. 상류가 만든 텍스트와
    숫자만 본다.
    """
    assert {name: handler.needs_video for name, handler in HANDLERS.items()} == {
        "scene_detection": True,
        "frame_extraction": True,
        "vlm_metadata": False,
        "ocr": False,
        "asr": True,
        "text_embedding": False,
        "indexing": False,
    }


def test_require_video_refuses_when_the_stage_declared_it_needs_none(tmp_path: Path) -> None:
    """등록과 구현이 어긋난 것이다. None 을 그대로 넘기면 벤더 라이브러리에서 터진다."""
    context = StageContext(
        stage="ocr",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/ocr/a1/",
    )
    with pytest.raises(AssertionError, match="needs_video"):
        context.require_video()


def test_ocr_declares_the_keyframes_it_must_read() -> None:
    """러너가 이 목록만 받아 온다. 빠지면 그 프레임을 읽지 못한다."""
    handler = resolve("ocr")
    assert handler is not None
    assert handler.required_inputs is not None

    keys = handler.required_inputs(_ocr_upstream((0, 4200), (1, 7300)))

    assert keys == (
        "runs/1/frame_extraction/a1/s0000/kf-000004200.jpg",
        "runs/1/frame_extraction/a1/s0001/kf-000007300.jpg",
    )


def test_ocr_does_not_ask_for_the_same_file_twice() -> None:
    upstream = _ocr_upstream((0, 4200), (0, 4200))
    handler = resolve("ocr")
    assert handler is not None
    assert handler.required_inputs is not None
    assert len(handler.required_inputs(upstream)) == 1


def test_ocr_rejects_upstream_without_frame_extraction() -> None:
    """영구 오류다. BE 가 다시 보내도 같은 것을 보낸다."""
    handler = resolve("ocr")
    assert handler is not None
    assert handler.required_inputs is not None
    with pytest.raises(UpstreamOutputInvalidError):
        handler.required_inputs({"sceneDetection": {}})


def test_ocr_refuses_to_read_when_an_image_was_not_fetched(tmp_path: Path) -> None:
    """일부만 읽고 성공으로 반납하면 "글자가 없었다" 는 거짓이 정본에 남는다."""
    handler = resolve("ocr")
    assert handler is not None
    context = StageContext(
        stage="ocr",
        # 러너가 `needs_video=False` 를 보고 영상을 해석하지 않는다.
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/ocr/a1/",
        upstream=_ocr_upstream((0, 4200)),
        upstream_files={},
    )
    with pytest.raises(UpstreamOutputInvalidError, match="받지 못했다"):
        handler.run(context)


def test_ocr_reports_counts_and_versions(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """어댑터가 순수 산출물을 봉투로 옮기는 부분. 모델 없이 확인한다."""
    import npick_worker.ocr as ocr_module
    from npick_worker.ocr import KeyframeRef, TextDetection, to_observations
    from npick_worker.ocr.models import OcrResult

    box = ((0.0, 0.0), (10.0, 0.0), (10.0, 4.0), (0.0, 4.0))

    def fake_read(keyframes: object, image_paths: object, **kwargs: object) -> OcrResult:
        keyframe = KeyframeRef(
            scene_index=0,
            timestamp_ms=4200,
            storage_key="runs/1/frame_extraction/a1/s0000/kf-000004200.jpg",
        )
        return OcrResult(
            keyframes=(
                to_observations(
                    keyframe,
                    [
                        TextDetection(text="강원도", confidence=0.99, points=box),
                        TextDetection(text="RG", confidence=0.29, points=box),
                    ],
                    min_confidence=0.7,
                ),
            ),
            config_version="ocr/v1:test",
            engine="fake",
            engine_version="0",
            tokenizer="query-norm/v1:test",
            min_confidence=0.7,
        )

    monkeypatch.setattr(ocr_module, "read_keyframes", fake_read)

    handler = resolve("ocr")
    assert handler is not None
    image = tmp_path / "kf.jpg"
    image.touch()
    outcome = handler.run(
        StageContext(
            stage="ocr",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/ocr/a1/",
            upstream=_ocr_upstream((0, 4200)),
            upstream_files={"runs/1/frame_extraction/a1/s0000/kf-000004200.jpg": image},
        )
    )

    assert outcome.metrics == {
        "keyframes": 1,
        "observations": 2,
        "unverifiedObservations": 1,
        "textGroups": 2,
        "minConfidence": 0.7,
    }
    assert len(outcome.uploads) == 1
    upload = outcome.uploads[0]
    saved = upload.local_path.read_bytes()
    assert upload.ref.kind == "ocr_result"
    assert upload.ref.storage_key == "runs/1/ocr/a1/ocr-result.json"
    assert upload.ref.content_hash == hashlib.sha256(saved).hexdigest()
    assert upload.ref.byte_size == len(saved)
    assert json.loads(saved)["output"] == outcome.output
    assert json.loads(saved)["outputSchemaVersion"] == "npick.stage.ocr.output/v2"
    assert outcome.versions.detail["mergeConfigVersion"] == outcome.output["mergeConfigVersion"]
    assert outcome.versions.config_version == "ocr/v1:test"
    # 가중치를 쓰는 첫 단계다. 앞의 둘과 달리 modelVersion 이 비어 있지 않다.
    assert outcome.versions.model_version == "fake/0"
    assert outcome.versions.prompt_version is None
    assert outcome.versions.detail["tokenizer"] == "query-norm/v1:test"


# ── vlm_metadata 의 배선 ─────────────────────────────────────────────

_VLM_OUTPUT = json.dumps(
    {
        "caption": {
            "value": "앵커가 스튜디오에서 소식을 전한다",
            "confidence": 0.9,
            "evidence": ["kf_1"],
        },
        "shot_type": {"value": "anchor", "confidence": 0.8, "evidence": ["kf_1"]},
        "scene_type": {"value": "스튜디오", "confidence": 0.7, "evidence": ["kf_1"]},
        "tag_candidates": [
            {"type": "location", "value": "서울", "confidence": 0.5, "evidence": ["kf_1"]}
        ],
    },
    ensure_ascii=False,
)


class _FakeVlmClient:
    """`VlmClient` 구현. 정해진 텍스트를 돌려준다."""

    name = "fake"
    version = "0"
    model_version = "fake-model@0"

    def __init__(self, output: str = _VLM_OUTPUT) -> None:
        self._output = output

    def describe(self, images: object, system_prompt: str, user_prompt: str, params: object) -> str:
        return self._output


def _vlm_files(tmp_path: Path, *keys: str) -> dict[str, Path]:
    """상류가 올린 keyframe 이 러너를 통해 도착한 모양. 바이트는 가짜여도 된다 —
    실제로 그것을 여는 쪽은 어댑터이고 여기서는 가짜 클라이언트를 쓴다."""
    files: dict[str, Path] = {}
    for key in keys:
        target = tmp_path / Path(key).name
        target.write_bytes(b"jpeg-bytes")
        files[key] = target
    return files


def test_vlm_declares_only_the_keyframes_it_will_send(tmp_path: Path) -> None:
    """상한을 넘는 장면에서는 **고른 것만** 받아 온다. 쓰지 않을 이미지를 내려받지 않는다."""
    handler = resolve("vlm_metadata")
    assert handler is not None
    assert handler.required_inputs is not None

    many = _ocr_upstream(*[(0, 1000 * step) for step in range(1, 9)])
    keys = handler.required_inputs(many)

    from npick_worker.vlm_metadata import get_default_config

    assert len(keys) == get_default_config().max_keyframes_per_scene
    # 양 끝이 들어 있다 — 앞에서 잘라내지 않는다.
    assert keys[0].endswith("kf-000001000.jpg")
    assert keys[-1].endswith("kf-000008000.jpg")


def test_vlm_rejects_upstream_without_frame_extraction() -> None:
    """영구 오류다. 빈 결과를 성공으로 반납하면 "설명할 장면이 없다" 는 거짓이 남는다."""
    handler = resolve("vlm_metadata")
    assert handler is not None
    assert handler.required_inputs is not None
    with pytest.raises(UpstreamOutputInvalidError):
        handler.required_inputs({})


def test_vlm_capability_excludes_failed_warmup_until_recovered(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from npick_worker.vlm_metadata import transformers_backend as backend
    from npick_worker.vlm_metadata.client import VlmModelUnavailableError

    client = backend.TransformersVlmClient("test/model", revision="main")
    monkeypatch.setattr(registry, "_vlm_client", lambda _: client)
    monkeypatch.setattr(backend.TransformersVlmClient, "version", property(lambda _: "fake"))
    monkeypatch.setattr(registry, "HANDLERS", {"vlm_metadata": HANDLERS["vlm_metadata"]})

    def fail(*args: object) -> object:
        raise VlmModelUnavailableError("load failed")

    monkeypatch.setattr(backend, "_load", fail)
    assert warm_up(["vlm_metadata"]).ready is False
    assert capability_versions() == {}
    sha = "a" * 40
    monkeypatch.setattr(backend, "_load", lambda *args: (object(), object(), sha))
    assert warm_up(["vlm_metadata"]).ready is True
    declared = capability_versions()["vlm_metadata"]
    assert client.model_version == f"test/model@{sha}"
    # 선언 조회가 모델을 다시 올리지 않으며 성공한 identity가 유지된다.
    monkeypatch.setattr(backend, "_load", fail)
    assert capability_versions()["vlm_metadata"] == declared


def test_vlm_payload_preflight_uses_largest_scene_not_clip_sum(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    upstream = _ocr_upstream((0, 1000), (0, 2000), (1, 3000), (1, 4000))
    keys = registry._vlm_selected_keys(upstream)
    files = _vlm_files(tmp_path, *keys)
    sizes = []

    def client(payload_bytes: int) -> _FakeVlmClient:
        sizes.append(payload_bytes)
        return _FakeVlmClient()

    monkeypatch.setattr(registry, "_vlm_client", client)
    ctx = StageContext(
        stage="vlm_metadata",
        video_path=None,
        storage_key="clip.mp4",
        work_dir=tmp_path,
        output_key_prefix="out/",
        upstream=upstream,
        upstream_files=files,
    )
    registry._run_vlm_metadata(ctx)
    assert sizes == [2 * len(b"jpeg-bytes")]


def test_vlm_stage_reports_every_version_and_metric(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """§7.2 기록 — 무엇이 이 결과를 만들었는지 다섯 축으로 남아야 한다."""
    monkeypatch.setattr(registry, "_vlm_client", lambda payload_bytes: _FakeVlmClient())
    upstream = _ocr_upstream((0, 4200))
    key = "runs/1/frame_extraction/a1/s0000/kf-000004200.jpg"
    context = StageContext(
        stage="vlm_metadata",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/vlm_metadata/a1/",
        upstream=upstream,
        upstream_files=_vlm_files(tmp_path, key),
    )

    outcome = registry.HANDLERS["vlm_metadata"].run(context)

    versions = outcome.versions
    assert versions.stage_version.startswith("npick.stage.vlm_metadata/v1:")
    assert versions.output_schema_version == "npick.stage.vlm_metadata.output/v2"
    assert versions.config_version is not None
    assert versions.config_version.startswith("vlm-metadata-config/v2:")
    # 앞의 세 단계에서 비어 있던 두 키가 여기서 처음 채워진다.
    assert versions.model_version == "fake-model@0"
    assert versions.prompt_version is not None
    assert versions.prompt_version.startswith("vlm-metadata-prompt/v2:")
    assert versions.detail["tokenizer"]
    assert "configVersion" not in versions.detail

    scene = outcome.output["scenes"][0]
    assert scene["shotType"]["value"] == "anchor"
    assert scene["caption"]["tokens"]
    # 장면 유형은 컬럼이 아니라 태그 후보로 나간다.
    assert {tag["type"] for tag in scene["tagCandidates"]} == {"scene_type", "location"}
    # 근거는 (sceneIndex, timestampMs) 쌍이다 — keyframe_id 는 BE 가 발급한다.
    assert scene["caption"]["evidence"][0] == {
        "sceneIndex": 0,
        "timestampMs": 4200,
        "storageKey": key,
    }
    assert outcome.metrics["scenes"] == 1
    assert outcome.metrics["captionedScenes"] == 1
    assert outcome.metrics["unknownShotTypes"] == 0
    assert outcome.metrics["keyframesSent"] == 1


def test_vlm_schema_failure_is_a_permanent_stage_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """계약 §9.2 — `VLM_SCHEMA_INVALID` 는 영구다. 재시도가 고치지 못한다."""
    monkeypatch.setattr(
        registry, "_vlm_client", lambda payload_bytes: _FakeVlmClient("설명하겠습니다: {")
    )
    key = "runs/1/frame_extraction/a1/s0000/kf-000004200.jpg"
    context = StageContext(
        stage="vlm_metadata",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/vlm_metadata/a1/",
        upstream=_ocr_upstream((0, 4200)),
        upstream_files=_vlm_files(tmp_path, key),
    )

    with pytest.raises(VlmOutputInvalidError) as caught:
        registry.HANDLERS["vlm_metadata"].run(context)

    assert classify(caught.value, "vlm_metadata") == ("VLM_SCHEMA_INVALID", False)


def test_unclassified_vlm_failure_is_not_labelled_a_schema_error() -> None:
    """정체 모를 예외에 영구 코드를 붙이면 원인과 분류가 동시에 거짓이 된다."""
    assert classify(RuntimeError("무슨 일인지 모른다"), "vlm_metadata") == ("STAGE_FAILED", True)


def test_external_backend_fails_closed_before_sending(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """PRD §12.4 — 조건이 확인되지 않으면 전송 전에 멈춘다."""
    monkeypatch.setenv("NPICK_AI_VLM_BACKEND", "external")
    get_settings.cache_clear()
    try:
        with pytest.raises(ExternalProcessingRefusedError, match="clip 의 외부 처리 권리"):
            registry._vlm_client(1024)
        record = next(r for r in caplog.records if hasattr(r, "authorization"))
        assert record.authorization["allowed"] is False
        assert record.authorization["payloadBytes"] == 1024
        assert record.authorization["payloadCategory"] == "selected_keyframes"
        assert "1024" in record.getMessage()
    finally:
        get_settings.cache_clear()


# ── text_embedding · indexing (S15P21A501-183) ───────────────────────


def _caption_upstream(*captions: str | None) -> dict[str, object]:
    """장면마다 캡션 하나. `None` 은 VLM 이 캡션을 내지 않은 장면이다.

    `tokens` 를 `value` 와 같게 둔다. 둘이 갈리는 경우는
    `test_indexing_counts_captions_by_index_tokens_not_by_prose` 가 따로 본다.
    """
    return {
        "vlmMetadata": {
            "scenes": [
                {
                    "sceneIndex": index,
                    "caption": None if caption is None else {"value": caption, "tokens": caption},
                }
                for index, caption in enumerate(captions)
            ]
        }
    }


def _fake_embed(monkeypatch: pytest.MonkeyPatch) -> None:
    """가중치 없이 어댑터만 확인한다. 차원은 동봉 설정을 그대로 따른다."""
    from collections.abc import Sequence

    import npick_worker.text_embedding as text_embedding

    class FakeEncoder:
        name = "fake"
        version = "0.0.0"
        model_version = "fake@0"

        def encode(self, texts: Sequence[str]) -> tuple[tuple[float, ...], ...]:
            dimension = text_embedding.get_default_config().dimension
            return tuple((1.0,) * dimension for _ in texts)

    real = text_embedding.embed_scenes
    monkeypatch.setattr(
        text_embedding,
        "embed_scenes",
        lambda scenes, **kwargs: real(scenes, encoder=FakeEncoder()),
    )


def test_text_embedding_reads_no_video_and_fetches_no_upstream_file() -> None:
    """캡션과 대사는 인라인·artifact 문서로 온다. 영상도 keyframe 도 열지 않는다."""
    handler = resolve("text_embedding")
    assert handler is not None
    assert handler.needs_video is False
    assert handler.required_inputs is None


def test_text_embedding_uploads_the_vectors_as_an_artifact(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """벡터를 payload 에 인라인하면 `stage_states_json` 이 장면 수만큼 부푼다."""
    _fake_embed(monkeypatch)
    handler = resolve("text_embedding")
    assert handler is not None
    context = StageContext(
        stage="text_embedding",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/text_embedding/a1/",
        upstream=_caption_upstream("부산 광안대교", "해운대 해수욕장"),
    )

    outcome = handler.run(context)

    assert len(outcome.uploads) == 1
    upload = outcome.uploads[0]
    assert upload.ref.kind == "scene_embeddings"
    assert upload.ref.storage_key == "runs/1/text_embedding/a1/embeddings.json"
    assert upload.content_type == "application/json"
    document = json.loads(upload.local_path.read_text(encoding="utf-8"))
    assert document["schemaVersion"] == "npick.scene.embeddings/v1"
    assert [scene["sceneIndex"] for scene in document["scenes"]] == [0, 1]
    assert len(document["scenes"][0]["vector"]) == document["dimension"]
    assert outcome.output["embeddingsArtifact"]["storageKey"] == upload.ref.storage_key
    assert outcome.output["embeddedCount"] == 2
    assert outcome.output["skippedSceneIndexes"] == []


def test_text_embedding_skips_a_scene_without_caption_or_dialogue(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """빈 문자열을 임베딩하면 빈 장면끼리 최근접이 된다. `scene.embedding` 은 nullable 이다."""
    _fake_embed(monkeypatch)
    handler = resolve("text_embedding")
    assert handler is not None
    context = StageContext(
        stage="text_embedding",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/text_embedding/a1/",
        upstream=_caption_upstream("부산 광안대교", None, "   "),
    )

    outcome = handler.run(context)

    assert outcome.output["embeddedCount"] == 1
    assert outcome.output["skippedSceneIndexes"] == [1, 2]
    document = json.loads(outcome.uploads[0].local_path.read_text(encoding="utf-8"))
    assert [scene["sceneIndex"] for scene in document["scenes"]] == [0]


def test_text_embedding_rejects_upstream_without_vlm_metadata(tmp_path: Path) -> None:
    """영구 오류다. BE 가 다시 보내도 같은 것을 보낸다."""
    handler = resolve("text_embedding")
    assert handler is not None
    context = StageContext(
        stage="text_embedding",
        video_path=None,
        storage_key="clips/1/source.mp4",
        work_dir=tmp_path,
        output_key_prefix="runs/1/text_embedding/a1/",
        upstream={"sceneDetection": {}},
    )
    with pytest.raises(UpstreamOutputInvalidError):
        handler.run(context)


def test_text_embedding_joins_mapped_dialogue_to_the_caption(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """FRD §11 은 캡션과 **대사**를 합쳐 벡터 하나를 만들라고 한다."""
    from .test_vlm_transcript_input import sample

    _fake_embed(monkeypatch)
    mapping_upstream, documents = sample()
    upstream = dict(_caption_upstream("첫 장면", "둘째 장면", "셋째 장면"))
    upstream["scene_transcript_mapping"] = mapping_upstream["scene_transcript_mapping"]
    handler = resolve("text_embedding")
    assert handler is not None

    outcome = handler.run(
        StageContext(
            stage="text_embedding",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/text_embedding/a1/",
            upstream=upstream,
            artifact_documents=documents,
        )
    )

    document = json.loads(outcome.uploads[0].local_path.read_text(encoding="utf-8"))
    sources = {scene["sceneIndex"]: scene["sourceText"] for scene in document["scenes"]}
    assert "부산 축제 소개" in sources[0]
    assert "첫 장면" in sources[0]
    # 2번 장면은 연결된 대사가 없다. 캡션만 남는다.
    assert "부산 축제 소개" not in sources[2]


def test_indexing_summarises_every_index_channel(tmp_path: Path) -> None:
    """색인 재료가 얼마나 찼는지를 숫자로 남긴다. 색인을 쓰지도 게시를 정하지도 않는다."""
    handler = resolve("indexing")
    assert handler is not None
    upstream: dict[str, object] = {
        "sceneDetection": {"scenes": [{"sceneIndex": index} for index in range(3)]},
        "ocr": {
            "observations": [
                {"sceneIndex": 0, "tokens": "부산"},
                {"sceneIndex": 0, "tokens": ""},
            ]
        },
        "textEmbedding": {"embeddedCount": 2},
    }
    upstream.update(_caption_upstream("부산 광안대교", None, "해운대"))
    upstream["scene_transcript_mapping"] = {
        "scenes": [
            {"sceneIndex": 0, "segments": [{"segmentId": "s1", "overlapMs": 1500}]},
            {"sceneIndex": 1, "segments": []},
            {"sceneIndex": 2, "segments": []},
        ]
    }

    outcome = handler.run(
        StageContext(
            stage="indexing",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/indexing/a1/",
            upstream=upstream,
        )
    )

    assert outcome.output == {
        "sceneCount": 3,
        "captionedScenes": 2,
        "dialogueScenes": 1,
        "ocrScenes": 1,
        "embeddedScenes": 2,
    }
    assert outcome.uploads == ()


def test_indexing_succeeds_when_every_text_channel_is_empty(tmp_path: Path) -> None:
    """게시 가능 판정은 `JdbcClipPublicationAdapter` 가 정본이다. 여기서 흉내내지 않는다."""
    handler = resolve("indexing")
    assert handler is not None

    outcome = handler.run(
        StageContext(
            stage="indexing",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/indexing/a1/",
            upstream={"sceneDetection": {"scenes": [{"sceneIndex": 0}]}},
        )
    )

    assert outcome.output == {
        "sceneCount": 1,
        "captionedScenes": 0,
        "dialogueScenes": 0,
        "ocrScenes": 0,
        "embeddedScenes": 0,
    }


def test_indexing_rejects_a_run_without_scenes(tmp_path: Path) -> None:
    """장면이 없으면 색인할 것도 없다. 치명 단계이므로 조용히 통과시키지 않는다."""
    handler = resolve("indexing")
    assert handler is not None
    with pytest.raises(UpstreamOutputInvalidError):
        handler.run(
            StageContext(
                stage="indexing",
                video_path=None,
                storage_key="clips/1/source.mp4",
                work_dir=tmp_path,
                output_key_prefix="runs/1/indexing/a1/",
                upstream={"sceneDetection": {"scenes": []}},
            )
        )


def test_indexing_needs_neither_video_nor_upstream_files() -> None:
    handler = resolve("indexing")
    assert handler is not None
    assert handler.needs_video is False
    assert handler.required_inputs is None


def test_indexing_always_declares_a_capability_version() -> None:
    """BE 는 목록에 없는 단계를 배정하지 않는다. 모델도 설정도 없는 단계라 항상 선언된다."""
    assert "indexing" in capability_versions()


def test_text_embedding_is_not_declared_without_a_model(monkeypatch: pytest.MonkeyPatch) -> None:
    """`vlm_metadata`·`asr` 와 같은 이유다. 배정받아 매번 죽는 것보다 낫다."""
    monkeypatch.setenv("NPICK_AI_EMBEDDING_MODEL", "")
    get_settings.cache_clear()
    try:
        assert "text_embedding" not in capability_versions()
    finally:
        get_settings.cache_clear()


def test_indexing_counts_captions_by_index_tokens_not_by_prose(tmp_path: Path) -> None:
    """게시 판정이 보는 것은 `scene.caption_tokens` 다.

    설명이 조사·기호뿐이면 `tokens` 가 빈 문자열이고(`CaptionOut` 이 허용한다) 그 장면은
    캡션 채널로 검색되지 않는다. 산문을 세면 요약은 "캡션 채널이 찼다" 고 말하는데 게시는
    안 되고, 사람이 요약을 열어도 왜인지 알 수 없다.
    """
    handler = resolve("indexing")
    assert handler is not None
    upstream: dict[str, object] = {
        "sceneDetection": {"scenes": [{"sceneIndex": 0}, {"sceneIndex": 1}]},
        "vlmMetadata": {
            "scenes": [
                {"sceneIndex": 0, "caption": {"value": "광안대교 야경", "tokens": "광안대교 야경"}},
                # 설명은 있는데 색인할 내용어가 없다.
                {"sceneIndex": 1, "caption": {"value": "그리고 그것은", "tokens": "  "}},
            ]
        },
    }

    outcome = handler.run(
        StageContext(
            stage="indexing",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/indexing/a1/",
            upstream=upstream,
        )
    )

    assert outcome.output["captionedScenes"] == 1


def test_indexing_does_not_validate_scene_fields_it_never_reads(tmp_path: Path) -> None:
    """장면 수만 센다. 쓰지 않는 필드를 검증하면 상류가 모양을 바꿀 때 색인이 죽는다."""
    handler = resolve("indexing")
    assert handler is not None

    outcome = handler.run(
        StageContext(
            stage="indexing",
            video_path=None,
            storage_key="clips/1/source.mp4",
            work_dir=tmp_path,
            output_key_prefix="runs/1/indexing/a1/",
            # `sceneIndex` 가 없다. 이 단계는 그 값을 읽지 않는다.
            upstream={"sceneDetection": {"scenes": [{"startTimeMs": 0}, {"startTimeMs": 1000}]}},
        )
    )

    assert outcome.output["sceneCount"] == 2


def test_text_embedding_is_not_declared_with_a_moving_revision(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`main` 같은 ref 는 로딩 전후로 `model_version` 이 달라진다.

    claim 에 실은 `stageVersion` 과 결과가 보고하는 값이 갈리고, 그게 계약 §7 의 버전
    불일치다. `vlm_metadata`·`asr` 가 워밍업 가드를 둔 이유와 같다.
    """
    monkeypatch.setenv("NPICK_AI_EMBEDDING_MODEL_REVISION", "main")
    get_settings.cache_clear()
    try:
        with pytest.raises(ModelUnavailableError, match="리비전"):
            registry._declared_version("text_embedding")
        assert "text_embedding" not in capability_versions()
    finally:
        get_settings.cache_clear()
