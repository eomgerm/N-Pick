"""승인된 GMS(OpenAI 호환 게이트웨이) HTTP 구현.

`ollama_backend.py` 와 같은 자리에 있는 두 번째 `QueryResolver` 구현이다. 둘 다
남겨 두는 이유는 FRD §13.4 가 "승인되지 않으면 local adapter 를 사용"을 fallback
경로로 지정하기 때문이다 — 로컬 구현을 지우면 그 문장을 만족할 수단이 없어진다.

`httpx2` 심볼과 API 키는 이 파일 밖으로 나가지 않는다. 예외도 전부
`ResolverCallError` 로 바꿔서 내보낸다.

**외부 전송 승인 검사가 아직 없다.** `NFR-GMS-002` 는 active deployment policy·
provider profile 이 없으면 외부 호출 **전에** fail-closed 하라고 요구하고, §13.4 는
deployment-level `query_external_processing_allowed=yes` 와 provider allowlist 를
요구한다. `deployment_external_policy`·`external_provider_profile` 테이블은 비어
있고 BE 도메인 코드도 없어서, 지금은 그 검사가 **어디에도 없다.** 검사가 생기면
`complete()` 진입부가 붙일 자리다. 이 파일이 그걸 대신 판단하지는 않는다 —
승인 정보는 배포 단위의 것이고 워커가 지어낼 수 없다.
"""

from typing import Any, Final

import httpx2

from npick_worker.query_resolver.config import CallParams
from npick_worker.query_resolver.resolver import ResolverCallError

#: FRD §12 오류 코드.
_TIMEOUT: Final[str] = "RESOLVER_TIMEOUT"
_RATE_LIMITED: Final[str] = "RESOLVER_RATE_LIMITED"
_NETWORK: Final[str] = "RESOLVER_NETWORK"

_TOO_MANY_REQUESTS: Final[int] = 429
_ERROR_STATUS: Final[int] = 400
#: 오류 본문을 얼마나 실을지. 전부 넣으면 로그가 HTML 페이지로 뒤덮인다.
_ERROR_BODY_LIMIT: Final[int] = 500

_CHAT_PATH: Final[str] = "/v1/chat/completions"
#: 전체 엔드포인트인지 판정하는 꼬리. `/v1` 을 붙이지 않는 게이트웨이도 있다.
_CHAT_SUFFIX: Final[str] = "/chat/completions"


def resolve_endpoint(base_url: str) -> str:
    """요청 URL 을 **한 번만** 만든다.

    게이트웨이가 안내하는 주소는 base 일 수도 있고 전체 엔드포인트일 수도 있다.
    SSAFY GMS 는 후자다 — `https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions`.
    둘 다 받는다. 어느 쪽인지 사람이 기억해야 하는 구조였고, 그래서 경로가 두 번
    붙어 실패했다. 조립을 여기 한 곳으로 모아 그 실패가 다시 생기지 않게 한다.
    """
    trimmed = base_url.rstrip("/")
    if trimmed.endswith(_CHAT_SUFFIX):
        return trimmed
    return trimmed + _CHAT_PATH


class GmsResolver:
    """`QueryResolver` Protocol 구현. OpenAI 호환 `chat/completions` 를 쓴다.

    `messages` 구조가 Ollama 와 같아서 상위의 system/user 분리를 그대로 쓴다.
    다른 곳은 두 군데다 — `max_tokens`(Ollama 는 `options.num_predict`)와
    `response_format`(Ollama 는 `format="json"`).
    """

    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        params: CallParams,
        *,
        json_mode: bool = True,
    ) -> None:
        for value, env in (
            (base_url, "NPICK_AI_GMS_BASE_URL"),
            (api_key, "NPICK_AI_GMS_API_KEY"),
            (model, "NPICK_AI_GMS_MODEL"),
        ):
            if not value:
                msg = f"{env} 이 비어 있다. 환경 변수로 지정한다"
                raise ValueError(msg)
        self._endpoint = resolve_endpoint(base_url)
        self._api_key = api_key
        self._model = model
        self._params = params
        self._json_mode = json_mode
        self._reported_model: str | None = None

    @property
    def name(self) -> str:
        return "gms"

    @property
    def version(self) -> str:
        """`model_version` 에 실릴 값.

        게이트웨이는 digest 를 주지 않으므로 모델명이 전부다. 다만 응답 body 의
        `model` 이 설정값보다 구체적인 경우가 있어(별칭 → 실제 버전) 호출 이후에는
        그걸 쓴다. 호출 전이면 설정값을 그대로 쓴다 — **지어내지 않는다.**
        """
        return self._reported_model or self._model

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        payload: dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            "stream": False,
            "temperature": self._params.temperature,
            # OpenAI 는 max_tokens 를 폐기하고 이 이름을 쓴다. 옛 이름을 보내면
            # 최신 모델이 400 으로 거부한다(실측: gpt-5.4-mini).
            "max_completion_tokens": self._params.max_output_tokens,
        }
        if self._json_mode:
            # 디코딩 단계 제약이라 프롬프트 지시보다 강하다 — RESOLVER_SCHEMA_INVALID 를
            # 줄인다. 게이트웨이가 이 키를 받지 않으면 4xx 가 오므로,
            # NPICK_AI_GMS_JSON_MODE=false 로 끄고 프롬프트 지시에만 의존한다.
            # 자동 재시도는 하지 않는다(FR-QRY-023).
            payload["response_format"] = {"type": "json_object"}

        data = self._post(self._endpoint, payload, self._params.timeout_seconds)
        reported = data.get("model")
        if isinstance(reported, str) and reported:
            self._reported_model = reported
        return self._extract_content(data)

    def _extract_content(self, data: dict[str, Any]) -> str:
        choices = data.get("choices")
        if not isinstance(choices, list) or not choices:
            msg = f"GMS 응답에 choices 가 없다: {sorted(data)}"
            raise ResolverCallError(msg, category=_NETWORK)
        message = choices[0].get("message") if isinstance(choices[0], dict) else None
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            msg = "GMS 응답에 choices[0].message.content 가 없다"
            raise ResolverCallError(msg, category=_NETWORK)
        return str(message["content"])

    def _post(self, url: str, payload: dict[str, Any], timeout: float) -> dict[str, Any]:
        """예외 변환은 `ollama_backend._post` 와 같은 규칙이다.

        절대 URL 하나로 보낸다. Client 의 base_url 과 상대 경로를 조합하면 조립
        지점이 둘로 갈리고, 그게 경로 중복 버그의 원인이었다.

        오류 메시지에 URL 만 넣는다. 키는 헤더에만 있고 문자열로 조립되는 곳이 없다.
        """
        headers = {"Authorization": f"Bearer {self._api_key}"}
        try:
            with httpx2.Client(timeout=timeout, headers=headers) as client:
                response = client.post(url, json=payload)
        except httpx2.TimeoutException as exc:
            msg = f"GMS 응답이 {timeout}초 안에 오지 않았다: {url}"
            raise ResolverCallError(msg, category=_TIMEOUT) from exc
        except httpx2.HTTPError as exc:
            msg = f"GMS 호출이 실패했다 ({url}): {exc}"
            raise ResolverCallError(msg, category=_NETWORK) from exc

        if response.status_code == _TOO_MANY_REQUESTS:
            msg = f"GMS 가 요청을 제한했다: {url}"
            raise ResolverCallError(msg, category=_RATE_LIMITED)
        if response.status_code >= _ERROR_STATUS:
            # 본문을 반드시 싣는다. 상태 코드만으로는 무엇이 거부됐는지 알 수 없고,
            # 그러면 원인을 찾으려고 손으로 다시 찔러보게 된다.
            detail = self._redact(response.text)[:_ERROR_BODY_LIMIT]
            msg = f"GMS 가 요청을 거부했다 ({url}): {response.status_code} {detail}"
            raise ResolverCallError(msg, category=_NETWORK)

        try:
            body = response.json()
        except ValueError as exc:
            detail = self._redact(response.text)[:_ERROR_BODY_LIMIT]
            msg = f"GMS 응답이 JSON 이 아니다 ({url}): {detail}"
            raise ResolverCallError(msg, category=_NETWORK) from exc
        if not isinstance(body, dict):
            msg = f"GMS 응답이 객체가 아니다: {type(body).__name__}"
            raise ResolverCallError(msg, category=_NETWORK)
        return body

    def _redact(self, text: str) -> str:
        """오류 본문에 키가 섞여 나오는 경우를 막는다.

        게이트웨이가 요청을 되돌려 보여주는 구현이 있다. 본문을 실어 나르기로
        결정한 이상 이 가림은 선택이 아니다.
        """
        return text.replace(self._api_key, "<redacted>")
