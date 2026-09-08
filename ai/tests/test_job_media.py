"""입력 미디어 해석. 공유 마운트와 다운로드 두 배포를 같은 코드가 처리한다."""

import hashlib
from pathlib import Path

import httpx2
import pytest

from npick_worker.jobs.client import JobApiClient
from npick_worker.jobs.errors import (
    ContentHashMismatchError,
    InputDownloadError,
    InputUnavailableError,
)
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


# ── 잘린 다운로드 ────────────────────────────────────────────────────
# 오류 없이 일찍 끝난 응답(프록시 타임아웃·BE 가 스트림 중간에 죽는 경우)은 부분
# 파일을 남긴다. 걸러내지 않으면 detect_scenes 가 잘린 mp4 를 돌려 틀린 scene
# 경계를 succeeded 로 정본에 넣는다 — 깨끗한 실패보다 나쁘다.


@pytest.mark.asyncio
async def test_truncated_download_is_rejected(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    """sizeBytes 가 해시 없을 때의 유일한 방어선이다."""
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"x" * 512))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="http", size_bytes=1 << 20)
    with pytest.raises(InputDownloadError):
        async with resolver.resolve("run-1", ref):
            pass


@pytest.mark.asyncio
async def test_truncated_download_is_transient(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    """해시 불일치와 다르다 — 다시 받으면 온전할 수 있다."""
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"x" * 512))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="http", size_bytes=1 << 20)
    with pytest.raises(InputDownloadError) as caught:
        async with resolver.resolve("run-1", ref):
            pass
    assert caught.value.retryable is True
    assert caught.value.error_code == "MEDIA_UNAVAILABLE"


@pytest.mark.asyncio
async def test_matching_size_passes(fake_backend: FakeBackend, job_client: JobApiClient) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="http", size_bytes=len(_VIDEO_BYTES))
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.path.exists()


# ── 해시 표현 차이 ───────────────────────────────────────────────────
# 계약 §4.1 이 인코딩을 못 박지 않았다. 여기서 갈리면 ContentHashMismatchError 는
# 영구(UNSUPPORTED_MEDIA)이고 계약 §9.2 가 영구를 "attempts 동결·재claim 없음" 으로
# 두므로, 치명 단계인 scene_detection 이 이걸로 죽으면 run 이 재시도 없이 failed 다.


@pytest.mark.parametrize(
    "render",
    [
        pytest.param(str.upper, id="대문자_hex"),
        pytest.param(lambda h: f"sha256:{h}", id="sha256_접두"),
        pytest.param(lambda h: f"  {h}  ", id="앞뒤_공백"),
        pytest.param(lambda h: f"SHA256:{h.upper()}", id="접두와_대문자"),
    ],
)
@pytest.mark.asyncio
async def test_content_hash_representation_does_not_matter(
    fake_backend: FakeBackend, job_client: JobApiClient, render: object
) -> None:
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(None, job_client)
    digest = hashlib.sha256(_VIDEO_BYTES).hexdigest()
    ref = MediaRef(
        storage_key="clips/a/source.mp4",
        transport="http",
        content_hash=render(digest),  # type: ignore[operator]
    )
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.path.exists()


@pytest.mark.asyncio
async def test_normalization_does_not_swallow_a_real_mismatch(
    fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    """표현 차이를 지우는 것이 진짜 불일치를 삼키면 안 된다."""
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=b"different"))
    resolver = MediaResolver(None, job_client)
    ref = MediaRef(
        storage_key="clips/a/source.mp4",
        transport="http",
        content_hash=hashlib.sha256(_VIDEO_BYTES).hexdigest().upper(),
    )
    with pytest.raises(ContentHashMismatchError):
        async with resolver.resolve("run-1", ref):
            pass


# ── 마운트가 없을 때 ─────────────────────────────────────────────────
# 계약 §5 는 http 를 필수, shared-volume 을 선택(최적화)으로 둔다. 마운트 오타
# 하나가 fleet 전체를 재시도 없는 100% 실패로 만드는 것은 그 의도와 반대다.


@pytest.mark.asyncio
async def test_shared_volume_falls_back_to_download_when_the_mount_is_gone(
    tmp_path: Path, fake_backend: FakeBackend, job_client: JobApiClient
) -> None:
    """BE 가 선언을 무시하고 shared-volume 을 보내도 느리게라도 성공해야 한다."""
    fake_backend.enqueue("artifact_get", httpx2.Response(200, content=_VIDEO_BYTES))
    resolver = MediaResolver(tmp_path / "없는마운트", job_client)
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="shared-volume")
    async with resolver.resolve("run-1", ref) as resolved:
        assert resolved.source == "download"
        assert resolved.path.read_bytes() == _VIDEO_BYTES


@pytest.mark.asyncio
async def test_fallback_needs_a_client(tmp_path: Path) -> None:
    """내려받을 수단이 없으면 폴백도 없다. 그때는 영구 실패가 정직하다."""
    resolver = MediaResolver(tmp_path / "없는마운트")
    ref = MediaRef(storage_key="clips/a/source.mp4", transport="shared-volume")
    with pytest.raises(InputUnavailableError):
        async with resolver.resolve("run-1", ref):
            pass
