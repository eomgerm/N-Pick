"""앱 합성부. 클라이언트와 러너가 같은 것을 말하는지 본다."""

from npick_worker.app import build_worker
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


def test_generated_worker_ids_differ_between_processes() -> None:
    # 같은 프로세스 안에서는 하나여야 하지만, 프로세스마다 달라야 한다.
    first, _ = build_worker(_settings())
    second, _ = build_worker(_settings())
    assert first.worker_id != second.worker_id
