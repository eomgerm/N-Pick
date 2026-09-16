"""배치 실행 모드. 배정이 없으면 프로세스가 스스로 끝나는지 본다.

SSAFY GPU 서버에는 상주 서버를 띄울 수 없다. 워커는 원래 pull 방식이라 인바운드가
없으므로, 잡 큐를 비우고 스스로 종료하는 실행 모드만 있으면 그 서버에서 데모 시드를
색인할 수 있다 (`docs/architecture/03-deployment.md`).

이 모듈의 테스트가 지키는 것은 **종료 조건과 종료 코드**다. 배치가 언제 멈춰야 하고
언제 멈추면 안 되는지, 그리고 색인이 실패했을 때 그것이 종료 코드로 나오는지다.
"""

import asyncio

import pytest

from npick_worker import drain as drain_module
from npick_worker.jobs.errors import JobApiUnauthorizedError, JobApiUnavailableError
from npick_worker.jobs.runner import ClaimOutcome
from npick_worker.settings import Settings

OK = ClaimOutcome.SUCCEEDED
BAD = ClaimOutcome.FAILED
IDLE = ClaimOutcome.IDLE
BUSY = ClaimOutcome.BACKPRESSURE


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

    def __init__(self, outcomes: list[ClaimOutcome | Exception]) -> None:
        self._outcomes = list(outcomes)
        self.calls = 0

    async def run_once(self) -> ClaimOutcome:
        self.calls += 1
        outcome = self._outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def _patch_build_worker(
    monkeypatch: pytest.MonkeyPatch, client: _FakeClient, runner: _FakeRunner
) -> None:
    monkeypatch.setattr(drain_module, "build_worker", lambda settings: (client, runner))


@pytest.fixture(autouse=True)
def _no_real_sleep(monkeypatch: pytest.MonkeyPatch) -> None:
    """백오프를 실제로 자지 않는다. 잔 시간이 아니라 재시도 여부가 관심사다."""

    async def quick_sleep(seconds: float) -> None:
        return None

    monkeypatch.setattr(asyncio, "sleep", quick_sleep)


# ── 종료 조건 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_drain_stops_when_there_is_no_assignment(monkeypatch: pytest.MonkeyPatch) -> None:
    """배정이 없으면(IDLE) 돌기를 멈추고 처리한 잡을 집계해 돌려준다.

    이게 없으면 배치 실행이 상주 폴링과 구별되지 않아 프로세스가 영영 끝나지 않는다.
    """
    client = _FakeClient()
    runner = _FakeRunner([OK, OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert (report.succeeded, report.failed) == (2, 0)
    assert report.processed == 2
    assert runner.calls == 3, "빈 응답을 받고도 계속 폴링했다"
    assert client.closed


@pytest.mark.asyncio
async def test_backpressure_does_not_end_the_batch(monkeypatch: pytest.MonkeyPatch) -> None:
    """`retryAfterMs` 는 BE 과부하지 큐가 비었다는 뜻이 아니다.

    여기서 멈추면 남은 시드 영상이 색인되지 않은 채 배치가 성공으로 끝난다.
    """
    client = _FakeClient()
    runner = _FakeRunner([BUSY, OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert report.succeeded == 1
    assert runner.calls == 3, "과부하 신호를 큐가 비었다고 읽었다"


# ── 실패 집계 ────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_failed_stages_are_counted_separately(monkeypatch: pytest.MonkeyPatch) -> None:
    """단계 실패를 성공으로 세면 시드가 전부 죽어도 종료 코드가 0 이 된다."""
    client = _FakeClient()
    runner = _FakeRunner([BAD, OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert (report.succeeded, report.failed) == (1, 1)
    assert report.processed == 2


# ── 오류 ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_drain_closes_the_client_even_when_the_loop_raises(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """인증 거절은 영구 오류다. 호출자에게 그대로 올린다 — 삼키면 실패가 묻힌다."""
    client = _FakeClient()
    runner = _FakeRunner([OK, JobApiUnauthorizedError("토큰이 거절됐다")])
    _patch_build_worker(monkeypatch, client, runner)

    with pytest.raises(JobApiUnauthorizedError):
        await drain_module.drain(_settings())

    assert client.closed


@pytest.mark.asyncio
async def test_transient_unavailability_does_not_kill_the_batch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """BE 재기동·네트워크 순단은 일시 오류다. 시드 30건 중 12번째에서 났다고
    나머지 18건을 버릴 이유가 없다."""
    client = _FakeClient()
    runner = _FakeRunner([OK, JobApiUnavailableError("순단"), OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert report.succeeded == 2
    assert client.closed


@pytest.mark.asyncio
async def test_persistent_unavailability_gives_up(monkeypatch: pytest.MonkeyPatch) -> None:
    """일시 오류를 무한히 참으면 배치가 끝나지 않는다. 연속 상한에서 포기한다."""
    limit = drain_module.MAX_CONSECUTIVE_UNAVAILABLE
    client = _FakeClient()
    runner = _FakeRunner([JobApiUnavailableError("계속 안 됨")] * (limit + 1))
    _patch_build_worker(monkeypatch, client, runner)

    with pytest.raises(JobApiUnavailableError):
        await drain_module.drain(_settings())

    assert runner.calls == limit, "상한을 넘겨 재시도했다"
    assert client.closed


@pytest.mark.asyncio
async def test_endless_backpressure_gives_up(monkeypatch: pytest.MonkeyPatch) -> None:
    """과부하에도 상한이 있어야 배치가 끝난다.

    BE 가 계속 `retryAfterMs` 를 내리면 이 루프는 영원히 돈다. 도달 실패와 달리
    던질 예외가 없으므로(BE 는 멀쩡히 응답한다) 포기했다는 사실을 보고로 남긴다 —
    조용히 끝내면 시드가 비었는데도 종료 코드가 0 이 된다.
    """
    limit = drain_module.MAX_CONSECUTIVE_BACKPRESSURE
    client = _FakeClient()
    runner = _FakeRunner([BUSY] * (limit + 1))
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert report.gave_up is True
    assert runner.calls == limit, "상한을 넘겨 계속 폴링했다"
    assert client.closed


@pytest.mark.asyncio
async def test_the_backpressure_streak_resets_after_a_job(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """상한은 **연속** 과부하에 대한 것이다. 사이에 잡이 처리되면 다시 센다."""
    limit = drain_module.MAX_CONSECUTIVE_BACKPRESSURE
    client = _FakeClient()
    runner = _FakeRunner([*([BUSY] * (limit - 1)), OK, *([BUSY] * (limit - 1)), OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert report.gave_up is False
    assert report.succeeded == 2


@pytest.mark.asyncio
async def test_the_unavailable_streak_resets_after_a_success(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """상한은 **연속** 실패에 대한 것이다. 사이에 잡이 처리되면 다시 센다.

    누적으로 세면 오래 도는 배치가 드문 순단만으로 중도 포기한다.
    """
    limit = drain_module.MAX_CONSECUTIVE_UNAVAILABLE
    fail = JobApiUnavailableError("순단")
    client = _FakeClient()
    runner = _FakeRunner([*([fail] * (limit - 1)), OK, *([fail] * (limit - 1)), OK, IDLE])
    _patch_build_worker(monkeypatch, client, runner)

    report = await drain_module.drain(_settings())

    assert report.succeeded == 2


# ── 진입점 ───────────────────────────────────────────────────────────


def _patch_main(
    monkeypatch: pytest.MonkeyPatch, report: drain_module.DrainReport, order: list[str]
) -> None:
    monkeypatch.setattr(drain_module, "get_settings", _settings)
    monkeypatch.setattr("npick_worker.jobs.registry.warm_up", lambda: order.append("warm_up"))

    async def fake_drain(settings: Settings) -> drain_module.DrainReport:
        order.append("drain")
        return report

    monkeypatch.setattr(drain_module, "drain", fake_drain)


def test_main_warms_up_before_draining(monkeypatch: pytest.MonkeyPatch) -> None:
    """워밍업이 먼저다. 뒤로 가면 첫 잡이 가중치 로딩을 물고 lease 를 태운다."""
    order: list[str] = []
    _patch_main(monkeypatch, drain_module.DrainReport(succeeded=1, failed=0), order)

    drain_module.main()

    assert order == ["warm_up", "drain"]


def test_main_exits_non_zero_when_the_batch_gave_up(monkeypatch: pytest.MonkeyPatch) -> None:
    """포기한 배치는 실패한 잡이 없어도 실패다. 큐를 비우지 못한 채 끝났다."""
    order: list[str] = []
    _patch_main(monkeypatch, drain_module.DrainReport(succeeded=1, failed=0, gave_up=True), order)

    with pytest.raises(SystemExit) as exc:
        drain_module.main()

    assert exc.value.code == 1


def test_main_exits_non_zero_when_a_stage_failed(monkeypatch: pytest.MonkeyPatch) -> None:
    """종료 코드가 0 이면 시드가 전부 죽어도 CI·운영자가 알 수 없다."""
    order: list[str] = []
    _patch_main(monkeypatch, drain_module.DrainReport(succeeded=3, failed=2), order)

    with pytest.raises(SystemExit) as exc:
        drain_module.main()

    assert exc.value.code == 1


def test_main_refuses_to_run_without_job_api_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    """설정을 빠뜨리면 base_url 이 빈 httpx 클라이언트가 만들어져 엉뚱한 곳에서 터진다.

    환경 주입이 가장 틀리기 쉬운 서버가 정확히 이 배치 실행 대상이다.
    """
    order: list[str] = []
    _patch_main(monkeypatch, drain_module.DrainReport(succeeded=0, failed=0), order)
    monkeypatch.setattr(drain_module, "get_settings", lambda: _settings(job_api_base_url=""))

    with pytest.raises(SystemExit) as exc:
        drain_module.main()

    assert exc.value.code == 2
    assert order == [], "설정이 없는데 워밍업과 폴링을 시작했다"
