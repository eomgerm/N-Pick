"""console script 진입점: `npick-worker-drain`.

**배치 실행 모드다.** 상주 워커(`__main__.py`)는 uvicorn 으로 뜨고 잡 루프를 lifespan
태스크로 돌리지만, SSAFY GPU 서버에는 상주 서버를 띄울 수 없다
(`docs/architecture/03-deployment.md`). 워커는 원래 pull 방식이라 인바운드가 없으므로,
포트 바인딩도 `/health` 도 없이 큐를 비우고 스스로 끝나는 진입점 하나면 그 서버에서
데모 시드를 색인할 수 있다.

루프는 새로 만들지 않는다. `JobRunner.run_once()` 가 잡 하나를 끝까지 처리하고 배정이
없을 때 False 를 주므로, 여기서 하는 일은 그 False 를 종료 신호로 읽는 것뿐이다.
"""

import asyncio
import logging

from npick_worker.app import build_worker
from npick_worker.jobs import registry
from npick_worker.settings import Settings, get_settings

logger = logging.getLogger(__name__)


async def drain(settings: Settings) -> int:
    """배정이 없을 때까지 잡을 처리하고 처리한 수를 돌려준다.

    예외는 삼키지 않는다 — 인증 거절이나 계약 위반을 성공으로 보고하면 시드가 비어
    있는데도 종료 코드가 0 이 된다. 다만 클라이언트는 어느 경로로 나가든 닫는다.
    """
    client, runner = build_worker(settings)
    processed = 0
    try:
        while await runner.run_once():
            processed += 1
    finally:
        await client.aclose()
    return processed


def main() -> None:
    settings = get_settings()
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s %(levelname)-8s %(name)s %(message)s",
    )
    registry.warm_up()
    processed = asyncio.run(drain(settings))
    logger.info("잡 %d건을 처리하고 배정이 없어 종료한다", processed)


if __name__ == "__main__":
    main()
