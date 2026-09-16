"""배치 실행 모드. 배정이 없으면 프로세스가 스스로 끝나는지 본다.

SSAFY GPU 서버에는 상주 서버를 띄울 수 없다. 워커는 원래 pull 방식이라 인바운드가
없으므로, 잡 큐를 비우고 스스로 종료하는 실행 모드만 있으면 그 서버에서 데모 시드를
색인할 수 있다 (`docs/architecture/03-deployment.md`).
"""

from typing import Any

import pytest

from npick_worker import drain as drain_module
from npick_worker.jobs.errors import JobApiUnauthorizedError
from npick_worker.settings import Settings


def _settings(**overrides: object) -> Settings:
    payload: dict[str, object] = {
        "job_poll_enabled": True,
        "job_api_base_url": "https://backend.test",
        "job_api_token": "t",
    }
    payload.update(overrides)
    return Settings(**payload)  # type: ignore[arg-type]


class _FakeClient:
    """`aclose` 만 있으면 된다. drain 이 클라이언트에 하는 일은 그것뿐이다."""

    def __init__(self) -> None:
        self.closed = False

    async def aclose(self) -> None:
        self.closed = True


class _FakeRunner:
    """`run_once` 결과를 미리 정해 둔다. 예외 인스턴스면 던진다."""

    def __init__(self, outcomes: list[Any]) -> None:
        self._outcomes = list(outcomes)
        self.calls = 0

    async def run_once(self) -> bool:
        self.calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return bool(outcome)


def _patch_build_worker(
    monkeypatch: pytest.MonkeyPatch, client: _FakeClient, runner: _FakeRunner
) -> None:
    monkeypatch.setattr(drain_module, "build_worker", lambda settings: (client, runner))


@pytest.mark.asyncio
async def test_drain_stops_when_there_is_no_assignment(monkeypatch: pytest.MonkeyPatch) -> None:
    """배정이 없으면(`run_once` 가 False) 돌기를 멈추고 처리한 잡 수를 돌려준다.

    이게 없으면 배치 실행이 상주 폴링과 구별되지 않아 프로세스가 영영 끝나지 않는다.
    """
    client = _FakeClient()
    runner = _FakeRunner([True, True, False])
    _patch_build_worker(monkeypatch, client, runner)

    processed = await drain_module.drain(_settings())

    assert processed == 2
    assert runner.calls == 3, "빈 응답을 받고도 계속 폴링했다"
    assert client.closed


@pytest.mark.asyncio
async def test_drain_closes_the_client_even_when_the_loop_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """인증 거절은 호출자에게 그대로 올린다 — 삼키면 시드 실패가 성공으로 보인다.

    그래도 클라이언트는 닫는다. 안 닫으면 httpx 연결 풀이 남는다.
    """
    client = _FakeClient()
    runner = _FakeRunner([True, JobApiUnauthorizedError("토큰이 거절됐다")])
    _patch_build_worker(monkeypatch, client, runner)

    with pytest.raises(JobApiUnauthorizedError):
        await drain_module.drain(_settings())

    assert client.closed
