"""버전 문자열 규약. 두 배포 단위가 함께 쓴다.

`ai/` 는 배포 단위 둘을 담는다 — 질의 리졸버와 파이프라인 워커
(`docs/architecture/02-container.md` 의 *요소* 표). 둘 사이에 공유되는 것은 많지 않지만
버전 필드의 형식만은 같아야 한다. 그 규약의 정본은 `docs/contracts/README.md` 이고
구현은 이 모듈 하나다.

    <schema>:<정규화 JSON 의 sha256 앞 N자>

예: `scene-detect/v1:20dfc0a6`. 같은 값이면 어느 프로세스에서 계산해도 같은 바이트열이
나와야 하므로 정규화 규칙(`sort_keys`·고정 separators·`ensure_ascii=False`)을 여기서
한 번만 정한다. BE 가 이 값을 Java 로 다시 계산하는 경우가 있어(`pipeline_version` 롤업)
규칙이 두 곳에 흩어지면 조용히 어긋난다.
"""

import hashlib
import json
from collections.abc import Mapping
from importlib.metadata import PackageNotFoundError, version
from typing import Any, Final

#: 해시 뒤에 붙는 길이. 충돌 확률보다 로그 가독성을 우선한 값이다.
HASH_LENGTH: Final[int] = 8


def canonical_json(payload: Mapping[str, Any]) -> str:
    """해시 입력용 정규화 JSON.

    `sort_keys` 와 고정 separators 를 쓰는 이유는 하나다. 같은 값이면 항상 같은
    바이트열이어야 한다. `ensure_ascii=False` 는 한글 값이 이스케이프되어 길어지는 것을
    막는다 — 해시 결과에는 영향이 없지만 디버깅할 때 사람이 읽을 수 있다.
    """
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def version_id(schema: str, payload: Mapping[str, Any], *, length: int = HASH_LENGTH) -> str:
    """`<schema>:<해시>` 를 만든다.

    `schema` 는 사람이 읽는 이름이고 해시는 그 안의 값들이 바뀌었는지를 말한다.
    schema 만으로는 "설정을 바꿨는데 이름이 그대로" 인 경우를 잡지 못하고, 해시만으로는
    무엇의 버전인지 알 수 없다. 둘 다 필요하다.
    """
    digest = hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()
    return f"{schema}:{digest[:length]}"


def service_version() -> str:
    """설치된 워커 패키지의 버전.

    `/health` 와 잡 API 의 `workerVersion` 이 같은 값을 써야 한다. 두 곳에서 따로
    계산하면 언젠가 갈라진다.
    """
    try:
        return version("npick-worker")
    except PackageNotFoundError:  # 설치되지 않은 채로 실행된 경우
        return "0.0.0+unknown"
