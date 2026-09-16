"""console script 진입점: `npick-worker-drain`.

**배치 실행 모드다.** 상주 워커(`__main__.py`)는 uvicorn 으로 뜨고 잡 루프를 lifespan
태스크로 돌리지만, SSAFY GPU 서버에는 상주 서버를 띄울 수 없다
(`docs/architecture/03-deployment.md`). 워커는 원래 pull 방식이라 인바운드가 없으므로,
포트 바인딩도 `/health` 도 없이 큐를 비우고 스스로 끝나는 진입점 하나면 그 서버에서
데모 시드를 색인할 수 있다.

루프는 새로 만들지 않는다. `JobRunner.run_once()` 가 잡 하나를 끝까지 처리하고 그
결말을 `ClaimOutcome` 으로 돌려주므로, 여기서 하는 일은 그 네 갈래를 **종료 조건과
종료 코드로 번역하는 것**뿐이다.
"""

import asyncio
import logging
from dataclasses import dataclass
from typing import Final

from npick_worker.app import build_worker
from npick_worker.jobs import registry
from npick_worker.jobs.errors import JobApiUnavailableError
from npick_worker.jobs.runner import ClaimOutcome
from npick_worker.settings import Settings, get_settings

logger = logging.getLogger(__name__)

#: 잡 API 에 닿지 못했을 때 쉬는 시간. BE 재기동·네트워크 순단을 넘긴다.
UNAVAILABLE_BACKOFF_SECONDS: Final[float] = 5.0

#: **연속** 도달 실패 상한. 이 횟수째 실패에서 포기하므로 재시도는 그보다 한 번
#: 적다. 없으면 BE 가 죽어 있을 때 배치가 영원히 끝나지 않아 배치 실행이라는 성질
#: 자체가 사라진다. 사이에 잡이 하나라도 처리되면 다시 센다.
MAX_CONSECUTIVE_UNAVAILABLE: Final[int] = 5

#: **연속** 과부하 상한. 같은 이유로 둔다 — BE 가 계속 `retryAfterMs` 를 내리면
#: 배치가 끝나지 않는다. 도달 실패보다 넉넉한 이유는 과부하가 고장이 아니라 정상
#: 신호이고, 무는 시간도 서버가 정해 `run_once` 안에서 이미 자기 때문이다.
MAX_CONSECUTIVE_BACKPRESSURE: Final[int] = 20


@dataclass(frozen=True, slots=True)
class DrainReport:
    """배치 한 번의 결말.

    **성공과 실패를 나눠 센다.** `run_once` 가 True 였다는 것은 잡을 받아 반납했다는
    뜻일 뿐이고, 단계가 실패해도 반납은 정상이다. 하나로 세면 시드가 전부 죽어도
    "10건 처리" 로 보고하고 0 으로 끝난다 — 시드 도구에서 그건 침묵한 실패다.
    """

    succeeded: int
    failed: int
    #: 큐를 비우지 못한 채 상한에 걸려 끝냈는가. 실패한 잡이 없어도 실패다.
    gave_up: bool = False

    @property
    def processed(self) -> int:
        return self.succeeded + self.failed


async def drain(settings: Settings) -> DrainReport:
    """배정이 없을 때까지 잡을 처리하고 결말을 집계해 돌려준다.

    정상적으로 멈추는 것은 `IDLE` 하나뿐이다. `BACKPRESSURE` 는 BE 가 과부하라 물린
    것이지 큐가 빈 것이 아니므로 계속 돈다 — 다만 영원히 돌 수는 없으므로 연속 상한을
    두고, 거기 걸리면 `gave_up` 으로 표시해 끝낸다. 예외를 던지지 않는 이유는 BE 가
    멀쩡히 응답하고 있어서다. 도달 실패로 보고하면 거짓이 된다.

    영구 오류(인증 거절·계약 위반)는 삼키지 않는다. 도달 실패만 일시로 보고 물러섰다
    다시 시도하되 연속 상한을 둔다. 어느 경로로 나가든 클라이언트는 닫는다.
    """
    client, runner = build_worker(settings)
    succeeded = failed = 0
    unavailable_streak = backpressure_streak = 0
    gave_up = False
    try:
        while True:
            try:
                outcome = await runner.run_once()
            except JobApiUnavailableError as exc:
                unavailable_streak += 1
                if unavailable_streak >= MAX_CONSECUTIVE_UNAVAILABLE:
                    raise
                logger.warning(
                    "잡 API 에 닿지 못했다 (%d/%d): %s",
                    unavailable_streak,
                    MAX_CONSECUTIVE_UNAVAILABLE,
                    exc,
                )
                await asyncio.sleep(UNAVAILABLE_BACKOFF_SECONDS)
                continue

            unavailable_streak = 0
            if outcome is ClaimOutcome.IDLE:
                break
            if outcome is ClaimOutcome.BACKPRESSURE:
                backpressure_streak += 1
                if backpressure_streak >= MAX_CONSECUTIVE_BACKPRESSURE:
                    logger.error(
                        "BE 가 %d회 연속으로 잡을 내주지 않았다. 큐를 비우지 못한 채 끝낸다",
                        backpressure_streak,
                    )
                    gave_up = True
                    break
                continue

            backpressure_streak = 0
            if outcome is ClaimOutcome.SUCCEEDED:
                succeeded += 1
            else:
                failed += 1
    finally:
        await client.aclose()
    return DrainReport(succeeded=succeeded, failed=failed, gave_up=gave_up)


def main() -> None:
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
    )
    if not settings.job_poll_enabled or not settings.job_api_base_url:
        # `app.lifespan` 은 이 조건에서 질의 리졸버로 갈라지지만 배치 실행에는 갈 곳이
        # 없다. 가드가 없으면 base_url 이 빈 클라이언트가 만들어져 첫 요청에서
        # 프로토콜 오류로 터진다 — 원인이 설정이라는 것이 드러나지 않는다.
        logger.error(
            "잡 API 설정이 없다. NPICK_AI_JOB_POLL_ENABLED 와 NPICK_AI_JOB_API_BASE_URL 을 준다"
        )
        raise SystemExit(2)

    registry.warm_up()
    report = asyncio.run(drain(settings))
    logger.info(
        "잡 %d건을 처리했다 (성공 %d · 실패 %d). 배정이 없어 종료한다",
        report.processed,
        report.succeeded,
        report.failed,
    )
    if report.failed or report.gave_up:
        # 종료 코드가 0 이면 시드가 전부 죽어도 호출한 쪽이 알 수 없다.
        raise SystemExit(1)


if __name__ == "__main__":
    main()
