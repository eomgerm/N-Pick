"""/health 계약과 단계 레지스트리가 FRD §5.1 과 일치하는지 검증한다."""

import pytest
from fastapi.testclient import TestClient

from npick_worker import app as app_module
from npick_worker.jobs.registry import WarmupReport
from npick_worker.jobs.runner import JobRunner

# FRD §5.1 표를 코드가 아니라 문서에서 옮겨 적은 값이다.
# stages.py 를 참조하면 검증이 자기 자신을 확인하는 셈이 되므로 하드코딩한다.
EXPECTED_STAGE_NAMES = [
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
]
EXPECTED_FATAL_STAGES = {"scene_detection", "frame_extraction", "indexing"}


def test_health_returns_ok(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "npick-ai-worker"
    assert body["version"]


def test_health_reports_device(client: TestClient) -> None:
    device = client.get("/health").json()["device"]

    assert device["resolved"] in {"cuda", "cpu"}
    # torch 미설치 환경에서도 헬스체크는 200 이어야 한다(gpu 그룹은 선택 의존성).
    if not device["torch_available"]:
        assert device["resolved"] == "cpu"
        assert device["torch_version"] is None
        assert device["cuda_available"] is False


def test_health_stage_registry_matches_frd(client: TestClient) -> None:
    pipeline = client.get("/health").json()["pipeline"]

    assert pipeline["stage_count"] == 10
    assert [s["name"] for s in pipeline["stages"]] == EXPECTED_STAGE_NAMES
    assert [s["order"] for s in pipeline["stages"]] == list(range(1, 11))
    assert {s["name"] for s in pipeline["stages"] if s["fatal"]} == EXPECTED_FATAL_STAGES


def test_health_reports_cold_warmup_when_polling_is_off(client: TestClient) -> None:
    """폴링이 꺼진 프로세스는 차가운 것이지 고장난 것이 아니다.

    여기서 실패를 내면 compose 헬스체크가 컨테이너를 재시작 루프에 빠뜨린다.
    """
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["warmup"]["enabled"] is False
    assert body["warmup"]["ready"] is False


def test_health_reports_polling_state(client: TestClient) -> None:
    """폴링이 꺼진 프로세스는 enabled 가 false 다.

    warmup 만으로는 폴링이 도는지 알 수 없다. 워밍업은 기동 때 한 번 끝나므로
    루프가 죽은 뒤에도 ready 가 true 로 남는다.
    """
    polling = client.get("/health").json()["polling"]

    assert polling["enabled"] is False
    assert polling["running"] is False


def test_health_says_so_when_the_job_loop_has_stopped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """폴링을 켰는데 루프가 끝났으면 그것이 밖에서 보여야 한다.

    이것이 이 필드를 만든 이유다. 루프가 죽어도 워밍업은 이미 끝나 있어서
    warmup.ready 는 true 로 남고, 컨테이너는 healthy 인데 잡을 하나도 안 가져간다.

    **status 는 200·"ok" 를 유지한다.** 여기서 실패를 내면 잘못된 토큰 하나로
    컨테이너가 재시작 루프에 빠진다 — 보이지만 노는 컨테이너보다 나쁘다.
    모니터링이 polling.running 을 본다.
    """
    monkeypatch.setenv("NPICK_AI_JOB_POLL_ENABLED", "true")
    monkeypatch.setenv("NPICK_AI_JOB_API_BASE_URL", "https://backend.test")
    monkeypatch.setenv("NPICK_AI_JOB_API_TOKEN", "t")
    # 워밍업은 scenedetect→cv2 임포트라 100MB 를 넘는다. 여기 관심사가 아니다.
    monkeypatch.setattr(
        app_module, "warm_up", lambda: WarmupReport(ready=True, device="cpu", stages=())
    )

    async def stops_immediately(self: object) -> None:
        """토큰이 거절돼 스스로 멈춘 루프와 같은 모양이다."""

    monkeypatch.setattr(JobRunner, "run", stops_immediately)

    with TestClient(app_module.create_app()) as client:
        body = client.get("/health").json()

    assert body["status"] == "ok"
    assert body["warmup"]["ready"] is True
    assert body["polling"]["enabled"] is True
    assert body["polling"]["running"] is False
