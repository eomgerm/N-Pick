"""앱 합성부. 클라이언트와 러너가 같은 것을 말하는지 본다."""

from pathlib import Path

from fastapi.testclient import TestClient

from npick_worker.app import build_worker, create_app
from npick_worker.settings import Settings


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
