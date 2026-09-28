"""/health 계약과 단계 레지스트리가 FRD §5.1 과 일치하는지 검증한다."""

from fastapi.testclient import TestClient

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
