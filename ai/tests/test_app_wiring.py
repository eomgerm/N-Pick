"""앱 합성부. 클라이언트와 러너가 같은 것을 말하는지 본다."""

import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from npick_worker.app import build_worker, create_app
from npick_worker.jobs.registry import declared_stages
from npick_worker.settings import Settings, get_settings

#: 느린 워밍업을 흉내내는 시간. 상한(0.1초)보다 충분히 커서 둘을 구분할 수 있어야 하고,
#: 테스트가 실제로 이만큼 기다리지는 않는다 — 단정을 끝내자마자 풀어 준다.
SLOW_WARM_UP_SECONDS = 10.0


def _settings(**overrides: object) -> Settings:
    payload: dict[str, object] = {
        "job_poll_enabled": True,
        "job_api_base_url": "https://backend.test",
        "job_api_token": "t",
    }
    payload.update(overrides)
    return Settings(**payload)  # type: ignore[arg-type]


def test_client_and_runner_share_one_worker_id() -> None:
    """헤더와 claim 본문이 다른 ID 를 말하면 BE 로그에서 워커를 추적할 수 없다."""
    client, runner = build_worker(_settings())
    assert client.worker_id == runner.worker_id
    assert client.worker_id != ""


def test_configured_worker_id_is_used_as_is() -> None:
    client, runner = build_worker(_settings(worker_id="runpod-a40-01"))
    assert client.worker_id == "runpod-a40-01"
    assert runner.worker_id == "runpod-a40-01"


def test_generated_worker_ids_are_unique_per_build() -> None:
    # 같은 프로세스 안에서는 하나여야 하지만, 프로세스마다 달라야 한다.
    first, _ = build_worker(_settings())
    second, _ = build_worker(_settings())
    assert first.worker_id != second.worker_id


# ── 공유 마운트 선언 ─────────────────────────────────────────────────
# compose 가 NPICK_AI_MEDIA_ROOT 를 무조건 주입하므로, 볼륨이 안 붙거나 경로가
# 어긋나면 BE 는 계속 transport: shared-volume 을 내려준다. 선언이 사실과 다르면
# 마운트 오타 하나가 fleet 전체를 실패시킨다.


def test_missing_media_root_is_not_declared_as_shared(tmp_path: Path) -> None:
    """설정됐지만 없는 경로면 sharedMediaVolume 을 거짓으로 선언한다."""
    _, runner = build_worker(_settings(media_root=tmp_path / "없는마운트"))

    assert runner._claim_request().worker.shared_media_volume is False


def test_present_media_root_is_declared_as_shared(tmp_path: Path) -> None:
    root = tmp_path / "media"
    root.mkdir()
    _, runner = build_worker(_settings(media_root=root))

    assert runner._claim_request().worker.shared_media_volume is True


def test_no_media_root_is_not_declared_as_shared() -> None:
    _, runner = build_worker(_settings())

    assert runner._claim_request().worker.shared_media_volume is False


# ── 리졸버 기동 (S15P21A501-164) ─────────────────────────────────────


def test_resolver_boot_warms_the_query_encoder(query_encoder_warmup: list[bool]) -> None:
    """폴링이 꺼진 프로세스가 질의 리졸버다. 그쪽은 `jobs.warm_up()` 을 타지 않는다.

    어댑터는 인스턴스만 만들고 가중치는 첫 `encode` 에서 올라가므로, 기동 시 깨우지
    않으면 **부팅 후 첫 검색**이 1.7GB 로딩을 물고 동기 예산(p95 10초)을 날린다.
    """
    with TestClient(create_app()):
        pass

    assert query_encoder_warmup == [True]


def test_slow_warm_up_does_not_block_the_boot(monkeypatch: pytest.MonkeyPatch) -> None:
    """**워밍업에 상한이 있다.**

    워밍업은 `yield` 앞이라 끝날 때까지 서버가 연결을 받지 않는다. 캐시 볼륨이 비어
    가중치를 원격에서 받는 콜드 스타트면 그 시간이 몇 분이 되고, 플랫폼의 startup probe
    유예를 넘기면 **재시작 루프**가 된다. 리졸버는 사용자 검색의 동기 경로 앞단이라
    그 루프가 바로 장애로 보인다.

    상한을 넘겨도 기동은 계속한다. 로딩은 스레드에서 이어지므로 버린 일이 아니고,
    그 사이의 검색만 dense 채널 없이 돈다(FRD v3.1 §6.2).
    """
    started = threading.Event()
    release = threading.Event()

    def never_finishes() -> bool:
        started.set()
        # 테스트가 풀어 준다. 타임아웃은 그래도 안 풀렸을 때의 안전장치다.
        release.wait(timeout=SLOW_WARM_UP_SECONDS)
        return True

    monkeypatch.setattr("npick_worker.app.warm_query_encoder", never_finishes)
    monkeypatch.setenv("NPICK_AI_EMBEDDING_WARMUP_TIMEOUT_SECONDS", "0.1")
    get_settings.cache_clear()

    begin = time.perf_counter()
    try:
        with TestClient(create_app()) as client:
            # **기동에 걸린 시간을 여기서 잰다.** "결국 200 이 나왔다" 로는 부족하다 —
            # 상한이 없어도 워밍업이 끝나기만 하면 그 단정은 통과한다. 재시작 루프를
            # 만드는 것은 실패가 아니라 **지연**이다.
            boot_seconds = time.perf_counter() - begin
            # 스레드를 먼저 풀어 준다. lifespan 종료가 기본 executor 를 기다리므로
            # 잡아 둔 채 빠져나가면 이번에는 종료가 막힌다.
            release.set()
            assert client.get("/health").status_code == 200
    finally:
        release.set()

    assert started.is_set(), "워밍업이 시작조차 하지 않았다"
    assert boot_seconds < SLOW_WARM_UP_SECONDS / 2, (
        f"워밍업이 끝날 때까지 기동이 막혔다 ({boot_seconds:.1f}초). "
        "상한이 없으면 콜드 스타트가 startup probe 를 넘긴다"
    )


# ── 선언 단계 제한 노브 (S15P21A501-186) ────────────────────────────


def test_health_reports_the_stages_this_deployment_declares(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """단계를 좁혔는지 밖에서 확인할 수 있어야 한다.

    `pipeline.stages` 는 FRD §5.1 표의 전사라 배포와 무관한 상수다. 그것만으로는 CPU
    워커와 GPU 파드가 무엇을 맡기로 했는지 구분되지 않는다.
    """
    monkeypatch.setenv("NPICK_AI_JOB_STAGES", "scene_detection,indexing")
    get_settings.cache_clear()

    with TestClient(create_app()) as client:
        pipeline = client.get("/health").json()["pipeline"]

    assert pipeline["declared"] == ["scene_detection", "indexing"]
    # 단계 표 자체는 노브가 건드리지 않는다.
    assert pipeline["stage_count"] == 10


def test_the_knob_narrows_what_the_claim_actually_carries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """선언이 좁아지는 지점은 `capability_versions()` 가 아니라 claim 본문이다.

    BE 가 보는 것은 이 목록뿐이고(계약 §4.1), 배정을 거르는 것도 이것이다.
    """
    monkeypatch.setenv("NPICK_AI_JOB_STAGES", "frame_extraction")
    get_settings.cache_clear()
    declared_stages.cache_clear()

    _, runner = build_worker(_settings())

    assert [c.stage for c in runner._claim_request().capabilities] == ["frame_extraction"]


def test_declared_stages_are_reported_in_pipeline_order(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """적은 순서가 아니라 단계 순서로 내놓는다.

    운영에서 두 워커의 `/health` 를 눈으로 맞대 보는 값이라 순서가 입력을 따라가면
    같은 목록이 배포마다 다르게 보인다.
    """
    monkeypatch.setenv("NPICK_AI_JOB_STAGES", "indexing,scene_detection")
    get_settings.cache_clear()
    declared_stages.cache_clear()

    with TestClient(create_app()) as client:
        declared = client.get("/health").json()["pipeline"]["declared"]

    assert declared == ["scene_detection", "indexing"]
