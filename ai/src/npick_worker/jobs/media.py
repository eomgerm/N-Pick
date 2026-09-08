"""입력 미디어를 로컬 경로로 만든다.

배포가 둘인데 계약은 하나여야 한다. compose 에서는 backend 와 ai-worker 가
`media:/srv/npick/media` 를 함께 마운트하므로 기가바이트짜리 원본을 HTTP 로 복사할 이유가
없다. RunPod 파드에는 공유 볼륨이 없고 아웃바운드 443 만 있다
(`docs/architecture/03-deployment.md`). 그래서 계약이 교환하는 것은 언제나 storage key 이고,
바이트가 실제로 어떻게 오는지는 배정의 `transport` 가 정한다. 코드는 한 벌이다.
"""

import hashlib
import logging
import tempfile
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Final, Literal

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import ContentHashMismatchError, InputUnavailableError
from npick_worker.jobs.models import MediaRef

logger = logging.getLogger(__name__)

_HASH_CHUNK_BYTES: Final[int] = 1 << 20

#: 로그·오류 메시지에서 절대 경로를 대신할 표시. FRD 는 응답과 로그에 절대 경로를
#: 노출하지 않도록 요구한다.
_MEDIA_PLACEHOLDER: Final[str] = "<media>"


@dataclass(frozen=True, slots=True)
class ResolvedInput:
    path: Path
    source: Literal["shared_mount", "download"]


class MediaResolver:
    """배정의 입력 참조를 실제로 열 수 있는 경로로 바꾼다."""

    def __init__(self, media_root: Path | None, client: JobApiClient | None = None) -> None:
        self._media_root = media_root.resolve() if media_root is not None else None
        self._client = client

    @asynccontextmanager
    async def resolve(self, run_id: str, ref: MediaRef) -> AsyncIterator[ResolvedInput]:
        """입력을 쓸 수 있는 동안만 유효한 경로를 내준다.

        내려받은 파일은 블록을 빠져나갈 때 지운다. 원본은 기가바이트급이고 파드 디스크는
        휘발성이지만 한 파드가 잡을 여러 개 처리하므로, 남겨 두면 금방 찬다.
        """
        if ref.transport == "shared-volume":
            yield ResolvedInput(path=self._shared_path(ref), source="shared_mount")
            return

        if self._client is None:
            msg = f"내려받을 수단이 없다: {ref.storage_key}"
            raise InputUnavailableError(msg)

        with tempfile.TemporaryDirectory(prefix="npick-input-") as tmp:
            dest = Path(tmp) / Path(ref.storage_key).name
            await self._client.download_input(run_id, ref.storage_key, dest)
            _verify_hash(dest, ref)
            yield ResolvedInput(path=dest, source="download")

    def _shared_path(self, ref: MediaRef) -> Path:
        """공유 마운트에서 파일을 찾는다. 키가 미디어 루트를 벗어나면 거절한다.

        `storage_key` 는 BE 가 준 값이지만 검증 없이 경로로 이어 붙이면 잡 API 가
        임의 파일 읽기 통로가 된다. media 는 사용자 경로가 아니라 검증된 ID 로만
        접근한다는 요구가 코드에서 지켜지는 지점이 여기다.
        """
        if self._media_root is None:
            msg = f"공유 마운트가 설정되지 않았다: {ref.storage_key}"
            raise InputUnavailableError(msg)

        key = ref.local_path or ref.storage_key
        if _is_absolute_anywhere(key):
            msg = f"절대 경로 키는 허용하지 않는다: {key}"
            raise InputUnavailableError(msg)

        candidate = (self._media_root / key).resolve()
        if not candidate.is_relative_to(self._media_root):
            msg = f"미디어 루트를 벗어난 키다: {key}"
            raise InputUnavailableError(msg)
        if not candidate.is_file():
            msg = f"입력 파일이 없다: {key}"
            raise InputUnavailableError(msg)
        return candidate


def _is_absolute_anywhere(key: str) -> bool:
    """POSIX 로도 Windows 로도 절대 경로가 아닌지 본다.

    `Path` 는 실행 중인 OS 의 규칙만 쓴다. Windows 에서 `Path("/etc/passwd")` 는
    절대 경로가 아니어서, 같은 키가 개발 머신과 리눅스 컨테이너에서 다른 검사에 걸린다.
    보안 검사가 플랫폼에 따라 달라지면 안 되므로 양쪽 규칙을 모두 본다.
    """
    return PurePosixPath(key).is_absolute() or PureWindowsPath(key).is_absolute()


def _verify_hash(path: Path, ref: MediaRef) -> None:
    """배정이 해시를 줬으면 대조한다.

    해시가 다르면 다시 받아도 같은 파일이 온다. 일시 오류가 아니다.
    """
    if ref.content_hash is None:
        return
    actual = sha256_file(path)
    if actual != ref.content_hash:
        msg = (
            f"입력 해시가 배정과 다르다: {ref.storage_key} (기대 {ref.content_hash}, 실제 {actual})"
        )
        raise ContentHashMismatchError(msg)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(_HASH_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def redact(text: str, *, media_root: Path | None, extra: Path | None = None) -> str:
    """오류 메시지에서 절대 경로를 지운다.

    메시지는 `stage_states_json` 을 거쳐 검수자 화면까지 갈 수 있다. 서버의 디렉터리
    구조가 거기 실릴 이유가 없다.
    """
    result = text
    for root in (extra, media_root):
        if root is None:
            continue
        for form in {str(root), str(root.resolve()), root.as_posix()}:
            result = result.replace(form, _MEDIA_PLACEHOLDER)
    return result
