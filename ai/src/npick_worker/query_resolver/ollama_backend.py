"""Ollama HTTP 구현.

`httpx2` 심볼은 이 파일 밖으로 나가지 않는다. 예외도 전부 `ResolverCallError` 로
바꿔서 내보낸다 — 그러지 않으면 adapter 경계를 둔 의미가 없다.

Ollama 가 설치되지 않은 환경에서도 **임포트는 성공한다.** 실패는 실제로 호출할 때만
난다. `ai/AGENTS.md` 의 "GPU 없이도 워커가 기동한다" 와 같은 취지다.
"""

from typing import Any, Final

import httpx2

from npick_worker.query_resolver.config import CallParams
from npick_worker.query_resolver.resolver import ResolverCallError

#: 모듈 오류 코드.
_TIMEOUT: Final[str] = "RESOLVER_TIMEOUT"
_RATE_LIMITED: Final[str] = "RESOLVER_RATE_LIMITED"
_NETWORK: Final[str] = "RESOLVER_NETWORK"

_TOO_MANY_REQUESTS: Final[int] = 429
#: model_version 에 넣을 digest 길이. 전체 sha256 은 varchar(128) 에 모델명과 같이 담기 부담스럽다.
_DIGEST_LENGTH: Final[int] = 12

#: `/api/show` 는 생성이 아니라 메타데이터 조회다. `timeout_seconds`(생성용)를 그대로 쓰면
#: 첫 요청이 최악 `timeout_seconds * 2` 가 되어 BE 의 read-timeout 을 넘고, 그러면 정규화
#: 결과(`search_tokens`)까지 잃어 §6.2 의 원 검색어 BM25 fallback 이 불가능해진다.
#: `[call]` toml 에 두지 않는 이유는 그 절 전체가 `prompt_version` 해시에 들어가기 때문이다 —
#: 전송 타임아웃이 바뀌었다고 "프롬프트가 달라졌다" 고 기록되면 §7.2 비교가 망가진다.
_METADATA_TIMEOUT_SECONDS: Final[float] = 1.0


class OllamaResolver:
    """`QueryResolver` Protocol 구현.

    `format="json"` 을 켜서 모델이 JSON 만 내도록 강제한다. 프롬프트로도 지시하지만
    이건 디코딩 단계 제약이라 더 강하다 — `RESOLVER_SCHEMA_INVALID` 를 줄인다.
    """

    def __init__(self, base_url: str, model: str, params: CallParams) -> None:
        if not model:
            msg = "NPICK_AI_OLLAMA_MODEL 이 비어 있다. 쓸 모델을 환경 변수로 지정한다"
            raise ValueError(msg)
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._params = params
        self._version: str | None = None
        #: digest 조회를 한 번만 하기 위한 표식. 성공만 캐시하면 조회가 실패하는 동안
        #: **모든** 요청이 `/api/show` 를 다시 부른다 — 첫 요청만의 비용이 아니게 된다.
        self._version_resolved = False

    @property
    def name(self) -> str:
        return "ollama"

    @property
    def version(self) -> str:
        """`<모델명>@<digest12>`.

        태그(`llama3.2:3b`)만으로는 부족하다 — 같은 태그를 다시 pull 하면 내용이
        달라질 수 있다. digest 를 조회할 수 없으면 태그만 쓰고, **지어내지 않는다.**
        """
        if not self._version_resolved:
            digest = self._fetch_digest()
            self._version = f"{self._model}@{digest}" if digest else self._model
            self._version_resolved = True
        return self._version or self._model

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "format": "json",
            "options": {
                "temperature": self._params.temperature,
                "num_predict": self._params.max_output_tokens,
            },
        }
        data = self._post("/api/chat", payload, self._params.timeout_seconds)
        message = data.get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            msg = f"Ollama 응답에 message.content 가 없다: {sorted(data)}"
            raise ResolverCallError(msg, category=_NETWORK)
        return str(message["content"])

    def _fetch_digest(self) -> str | None:
        """`/api/show` 로 모델 digest 를 읽는다. 실패하면 `None` — 호출을 막지 않는다."""
        try:
            data = self._post("/api/show", {"model": self._model}, _METADATA_TIMEOUT_SECONDS)
        except ResolverCallError:
            return None
        digest = data.get("digest")
        if isinstance(digest, str) and digest:
            return digest.removeprefix("sha256:")[:_DIGEST_LENGTH]
        return None

    def _post(self, path: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        try:
            with httpx2.Client(base_url=self._base_url, timeout=timeout) as client:
                response = client.post(path, json=payload)
                if response.status_code == _TOO_MANY_REQUESTS:
                    msg = f"Ollama 가 요청을 제한했다: {path}"
                    raise ResolverCallError(msg, category=_RATE_LIMITED)
                response.raise_for_status()
                body = response.json()
        except httpx2.TimeoutException as exc:
            msg = f"Ollama 응답이 {timeout}초 안에 오지 않았다: {path}"
            raise ResolverCallError(msg, category=_TIMEOUT) from exc
        except httpx2.HTTPError as exc:
            msg = f"Ollama 호출이 실패했다 ({self._base_url}{path}): {exc}"
            raise ResolverCallError(msg, category=_NETWORK) from exc
        if not isinstance(body, dict):
            msg = f"Ollama 응답이 객체가 아니다: {type(body).__name__}"
            raise ResolverCallError(msg, category=_NETWORK)
        return body
