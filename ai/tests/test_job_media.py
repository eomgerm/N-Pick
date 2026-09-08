"""입력 미디어 해석. 공유 마운트와 다운로드 두 배포를 같은 코드가 처리한다."""

import hashlib
from pathlib import Path

import httpx2
import pytest

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import ContentHashMismatchError, InputUnavailableError
from npick_worker.jobs.media import MediaResolver, redact
from npick_worker.jobs.models import MediaRef

from .conftest import FakeBackend

_VIDEO_BYTES = b"fake-video-bytes"


@pytest.fixture
def media_root(tmp_path: Path) -> Path:
    root = tmp_path / "media"
    (root / "clips" / "a").mkdir(parents=True)
    (root / "clips" / "a" / "source.mp4").write_bytes(_VIDEO_BYTES)
    return root


# ── 공유 마운트 ──────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_shared_mount_is_used_without_downloading(
    media_root: Path, fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    resolver = MediaResolver(media_root, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="shared-volume")
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.source == "shared_mount"
        assert resolved.path.read_bytes() == _VIDEO_BYTES
    # 700MB 원본을 HTTP 로 복사하지 않는다.
    assert fake_backend.calls("artifact_get") == []


@pytest.mark.asyncio
async def test_path_traversal_key_is_rejected(media_root: Path) -> None:
    resolver = MediaResolver(media_root)
    ref = MediaRef(storage_key="../../etc/passwd", transport="shared-volume")
    with pytest.raises(InputUnavailableError, match="벗어난"):
        async with resolver.resolve("run-1", ref):
            pass


@pytest.mark.parametrize("key", ["/etc/passwd", r"C:\Windows\win.ini", r"\\host\share"])
@pytest.mark.asyncio
async def test_absolute_key_is_rejected_on_every_platform(media_root: Path, key: str) -> None:
    # 같은 키가 개발 머신과 리눅스 컨테이너에서 다르게 판정되면 안 된다.
    resolver = MediaResolver(media_root)
    ref = MediaRef(storage_key=key, transport="shared-volume")
    with pytest.raises(InputUnavailableError, match="절대 경로"):
        async with resolver.resolve("run-1", ref):
            pass


@pytest.mark.asyncio
async def test_missing_file_is_input_unavailable(media_root: Path) -> None:
    resolver = MediaResolver(media_root)
    ref = MediaRef(storage_key="clips/a/nope.mp4", transport="shared-volume")
    with pytest.raises(InputUnavailableError):
        async with resolver.resolve("run-1", ref):
            pass


@pytest.mark.asyncio
async def test_shared_transport_without_media_root_is_rejected() -> None:
    resolver = MediaResolver(None)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="shared-volume")
    with pytest.raises(InputUnavailableError):
        async with resolver.resolve("run-1", ref):
            pass


# ── 다운로드 폴백 (RunPod) ───────────────────────────────────────────


@pytest.mark.asyncio
async def test_download_writes_a_temp_file(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="http")
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.source == "download"
        assert resolved.path.read_bytes() == _VIDEO_BYTES


@pytest.mark.asyncio
async def test_download_temp_file_is_removed(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="http")
    async with resolver.resolve("run-1", ref) as resolved:
        path = resolved.path
    assert not path.exists()


@pytest.mark.asyncio
async def test_download_verifies_content_hash(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(
        storage_key="clips/a/source.mp4",
        transport="http",
        content_hash=hashlib.sha256(_VIDEO_BYTES).hexdigest(),
    )
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.path.exists()


@pytest.mark.asyncio
async def test_hash_mismatch_is_permanent(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    # 다시 받아도 같은 파일이 온다. 재시도가 고칠 수 없다.
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"different"))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(
        storage_key="clips/a/source.mp4",
        transport="http",
        content_hash=hashlib.sha256(_VIDEO_BYTES).hexdigest(),
    )
    with pytest.raises(ContentHashMismatchError) as caught:
        async with resolver.resolve("run-1", ref):
            pass
    assert caught.value.retryable is False


# ── 경로 마스킹 ──────────────────────────────────────────────────────


def test_redact_removes_the_media_root(media_root: Path) -> None:
    message = f"파일을 열 수 없다: {media_root / 'clips' / 'a' / 'source.mp4'}"
    assert str(media_root) not in redact(message, media_root=media_root)


def test_redact_keeps_the_rest_of_the_message(media_root: Path) -> None:
    assert "파일을 열 수 없다" in redact(f"파일을 열 수 없다: {media_root}", media_root=media_root)


@pytest.mark.asyncio
async def test_download_404_is_permanent_media_unavailable(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    """MediaResolver 경유로도 영구 오류여야 한다. 일시로 보고하면 재시도 예산을 태운다."""
    fake_backend.enqueue_status("artifact_get", 404, code="JOB_404_002")
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/gone.mp4", transport="http")
    with pytest.raises(InputUnavailableError) as caught:
        async with resolver.resolve("run-1", ref):
            pass
    assert caught.value.error_code == "MEDIA_UNAVAILABLE"
    assert caught.value.retryable is False
