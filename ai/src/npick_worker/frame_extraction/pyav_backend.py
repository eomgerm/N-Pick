"""PyAV 기반 프레임 추출.

디코드 백엔드를 PyAV 로 고정하는 이유는 `scene_detection` 과 같다 — 휠에 ffmpeg 이
번들되어 시스템 설치가 필요 없고, 백엔드가 환경에 따라 갈리면 같은 파일에서 프레임
번호와 픽셀이 달라져 멱등성이 깨진다.

**디코드를 두 번 한다.** 한 번은 후보를 재고(`measure`), 한 번은 고른 프레임을 쓴다
(`write`). 한 번에 끝내려면 후보 프레임을 메모리에 들고 있거나 후보를 전부 인코드한 뒤
버려야 한다. 전자는 장면당 `max_keyframes` 와 `candidates_per_slot` 의 곱만큼을 원본
해상도로 쥐는 것이고 후자는 쓸 장 수의 세 배를 디스크에 쓴 뒤 지우는 것이다. 지금은 둘 다 하지
않는다 — **후보 때문에** 메모리·디스크가 늘어나지 않는 편을 골랐고, 필요해지면
`FrameGrabber` Protocol 뒤에서 바꿀 수 있다.

저장하는 keyframe 자체는 장면 수에 비례한다. 그건 이 선택으로 줄어드는 것이 아니고
`ai/docs/frame-extraction.md` §6 에 상한과 후속 과제를 적어 두었다.
"""

import hashlib
from collections.abc import Iterator, Mapping, Sequence
from pathlib import Path

import av
import numpy as np
from av.video.reformatter import ColorRange

from npick_worker.frame_extraction.config import FrameExtractionConfig
from npick_worker.frame_extraction.extractor import (
    MediaProfile,
    SceneRequest,
    WrittenImage,
)
from npick_worker.frame_extraction.selector import ScoredFrame
from npick_worker.media_errors import MediaUnreadableError
from npick_worker.timecode import frames_to_ms

#: 인코더에 넘기는 픽셀 형식. JPEG 은 full-range 이므로 색 범위를 **명시적으로** 준다.
#:
#: 관용적인 지정은 `yuvj420p` 인데, 그 이름은 ffmpeg 에서 폐기 예정이라 프레임을 변환할
#: 때마다 swscaler 가 "deprecated pixel format used, make sure you did set range
#: correctly" 를 찍는다. 장면 수백 개면 그 줄이 수백 줄이다.
#:
#: `yuv420p` + 색 범위 JPEG 이 같은 뜻이지만, **변환 시점에도 목표 범위를 줘야 한다.**
#: 인코더에만 주고 `reformat` 에 주지 않으면 swscaler 가 기본 범위로 변환해 바이트가
#: 달라진다(실측). 둘 다 주면 `yuvj420p` 경로와 sha256 까지 동일하다 — 밝기 범위가
#: 좁아지면 어두운 화면의 작은 글자가 먼저 뭉개지므로 이 동일성은 OCR 품질 문제다.
_JPEG_PIX_FMT = "yuv420p"

#: 단일 이미지 컨테이너. `image2` 는 파일명에 `%03d` 같은 시퀀스 패턴이 없다고 프레임마다
#: 경고를 두 줄씩 찍는다(장면 수백 개면 수천 줄이다). `mjpeg` 원시 muxer 는 같은 바이트를
#: 쓰면서 경고가 없다 — 실측으로 두 경로의 산출 크기가 동일함을 확인했다.
_JPEG_CONTAINER_FORMAT = "mjpeg"

#: 인코더 스레드 수를 **고정한다.** 기본값(auto)은 CPU 코어 수를 따라가고, mjpeg 는
#: 스레드 수에 따라 슬라이스를 나누므로 **산출 바이트가 기계마다 달라진다** — 같은
#: 프레임이 실측에서 1스레드 72,991B / 4스레드 이상 74,599B 로 나왔다(sha256 도 다르다).
#: 그러면 "같은 입력 + 같은 재현 식별자 = 같은 결과" 가 개발 머신과 GPU 파드 사이에서
#: 깨진다. 이미지 한 장 인코드에 스레드를 쓸 이유도 없다.
_JPEG_ENCODER_THREADS = 1


class PyAvFrameGrabber:
    """`FrameGrabber` Protocol 구현체."""

    @property
    def name(self) -> str:
        return "pyav"

    @property
    def version(self) -> str:
        """PyAV 와 numpy 버전을 함께 적는다.

        둘 다 결과를 바꾼다. PyAV(ffmpeg)가 바뀌면 같은 프레임 번호가 다른 픽셀을 줄
        수 있고 저장된 JPEG 바이트도 달라진다. numpy 가 바뀌면 같은 픽셀에서 점수가
        미세하게 달라져 **다른 프레임이 대표로 뽑힐 수 있다.** 한쪽만 적으면 재현
        식별자가 같은데 결과가 다른 경우가 생긴다.
        """
        return f"{av.__version__}+numpy{np.__version__}"

    def profile(self, video_path: Path) -> MediaProfile:
        with av.open(str(video_path)) as container:
            return _profile(container)

    def measure(
        self,
        video_path: Path,
        requests: Sequence[SceneRequest],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, Mapping[int, ScoredFrame]]:
        """후보 프레임을 재서 `scene_index → {프레임 번호: 측정값}` 을 돌려준다."""
        owner: dict[int, int] = {
            frame_number: request.scene_index
            for request in requests
            for slot in request.slots
            for frame_number in slot.frame_numbers
        }
        measured: dict[int, dict[int, ScoredFrame]] = {
            request.scene_index: {} for request in requests
        }
        if not owner:
            return measured

        with av.open(str(video_path)) as container:
            frame_rate = _profile(container).frame_rate
            for frame_number, frame in _decode_until(container, max(owner)):
                scene_index = owner.get(frame_number)
                if scene_index is None:
                    continue
                score, luma_std = _score(frame, cfg.score_stride)
                measured[scene_index][frame_number] = ScoredFrame(
                    frame_number=frame_number,
                    timestamp_ms=frames_to_ms(frame_number, frame_rate),
                    score=score,
                    luma_std=luma_std,
                    histogram=_histogram(frame, cfg.score_stride, cfg.change_hist_bins),
                )
        return measured

    def write(
        self,
        video_path: Path,
        targets: Mapping[int, Path],
        cfg: FrameExtractionConfig,
    ) -> Mapping[int, WrittenImage]:
        """고른 프레임을 원본 해상도 JPEG 으로 쓴다."""
        if not targets:
            return {}
        written: dict[int, WrittenImage] = {}
        with av.open(str(video_path)) as container:
            for frame_number, frame in _decode_until(container, max(targets)):
                target = targets.get(frame_number)
                if target is None:
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                _encode_jpeg(frame, target, cfg.jpeg_qscale)
                body = target.read_bytes()
                written[frame_number] = WrittenImage(
                    path=target,
                    byte_size=len(body),
                    content_sha256=hashlib.sha256(body).hexdigest(),
                )
        return written


def _profile(container: "av.container.InputContainer") -> MediaProfile:
    """열린 컨테이너에서 디코드 성질을 읽는다.

    프레임레이트로 `average_rate` 를 쓴다. PySceneDetect 의 `VideoStreamAv` 가 같은 값을
    쓰기 때문이다 — 다른 값을 쓰면 `scene_detection` 이 만든 ms 를 프레임 번호로 되돌릴
    때 어긋나고, keyframe 이 자기 scene 밖의 프레임을 가리킬 수 있다.
    """
    if not container.streams.video:
        msg = "비디오 스트림이 없는 파일이다"
        raise MediaUnreadableError(msg)
    stream = container.streams.video[0]
    if stream.average_rate is None or float(stream.average_rate) <= 0:
        msg = "프레임레이트를 읽을 수 없다"
        raise MediaUnreadableError(msg)
    width = stream.codec_context.width
    height = stream.codec_context.height
    if width <= 0 or height <= 0:
        msg = f"해상도를 읽을 수 없다: {width}x{height}"
        raise MediaUnreadableError(msg)
    return MediaProfile(frame_rate=float(stream.average_rate), width=width, height=height)


def _decode_until(
    container: "av.container.InputContainer", last_frame: int
) -> Iterator[tuple[int, "av.VideoFrame"]]:
    """0번부터 `last_frame` 까지 순차 디코드한다.

    탐색(seek)하지 않는다. 탐색은 키프레임 정렬 때문에 원하는 프레임을 놓칠 수 있고
    (`scene_detection/report.py` 의 같은 판단), 무엇보다 컨테이너의 GOP 구조에 따라
    결과가 달라지면 멱등성이 깨진다.

    프레임 번호는 **디코드 순서**다. `scene_detection` 이 ms 를 만들 때 쓴 번호 체계와
    같아야 하므로 PTS 로 바꾸지 않는다.
    """
    for frame_number, frame in enumerate(container.decode(container.streams.video[0])):
        yield frame_number, frame
        if frame_number >= last_frame:
            return


def _score(frame: "av.VideoFrame", stride: int) -> tuple[float, float]:
    """`(선명도, 휘도 표준편차)`.

    선명도는 라플라시안 분산이다. 초점이 맞고 윤곽이 살아 있는 프레임에서 크고, 모션
    블러·디졸브 중간·단색 화면에서 작다. 뉴스 화면의 자막·슈퍼는 고주파 성분이 강해
    이 값이 큰 쪽으로 몰리는데, 그것이 OCR 대상으로도 대표 이미지로도 우리가 원하는
    방향이다.

    가중치를 학습하거나 미학적 점수를 매기지 않는다. 그런 기준은 개발셋 측정 없이
    고를 수 없고(FRD §11), 여기서 필요한 것은 "블러·암전을 피한다" 뿐이다.

    휘도 표준편차를 따로 돌려주는 이유는 암전·화이트아웃이 **선명도로는 구분되지 않기**
    때문이다. 단색 프레임의 라플라시안도 0 에 가깝지만 잡음이 조금 섞이면 흐릿한
    실사 프레임보다 높게 나올 수 있다.
    """
    luma = frame.reformat(format="gray").to_ndarray()[::stride, ::stride].astype(np.float32)
    if luma.shape[0] < 3 or luma.shape[1] < 3:
        # 이웃 4개를 뺄 수 없다. stride 를 너무 키운 설정이거나 극단적으로 작은 영상이다.
        return 0.0, float(luma.std())
    laplacian = (
        4 * luma[1:-1, 1:-1] - luma[:-2, 1:-1] - luma[2:, 1:-1] - luma[1:-1, :-2] - luma[1:-1, 2:]
    )
    return float(laplacian.var()), float(luma.std())


def _histogram(frame: "av.VideoFrame", stride: int, bins: int) -> tuple[float, ...]:
    """H·S·V 채널별 히스토그램을 이어 붙인 것. 변화량 판정의 입력이다.

    **선명도와 같은 stride 로 읽은 픽셀에서 만든다.** 두 측정이 다른 픽셀 집합을 보면
    "이 프레임이 왜 뽑혔는가" 를 한 벌의 근거로 설명할 수 없다.

    ffmpeg 에 HSV 픽셀 형식이 없어서 RGB 로 받아 여기서 변환한다. `reformat(format="hsv")`
    는 `not a pixel format` 으로 죽는다. 변환을 numpy 로 하는 편이 `cv2` 를 이 패키지의
    의존성으로 끌어오는 것보다 낫다 — CPU 전용 fleet 이 frame extraction 만 돌릴 때
    `scene_detection` 의 무거운 의존성을 따라오게 하지 않는 것이 이 모듈의 전제다.

    채널을 이어 붙일 뿐 3차원 결합 히스토그램을 만들지 않는다. 결합 히스토그램은
    `bins ** 3` 칸이라 stride 로 솎아낸 픽셀 수로는 대부분이 비고, 그 성김이 코사인거리를
    실제 변화가 아니라 표본 잡음에 반응하게 만든다.

    각 채널을 픽셀 수로 나눠 정규화한다. 해상도가 다른 영상끼리 같은 임계값을 쓰려면
    히스토그램이 절대 개수가 아니라 비율이어야 한다.

    **디코드는 여기서 늘지 않는다.** `measure` 가 이미 그 프레임을 손에 들고 있고,
    `_decode_until` 은 후보 수와 무관하게 0번부터 순차로 훑는다. 늘어나는 것은 후보
    한 장당 변환·집계 비용뿐이다.
    """
    rgb = frame.reformat(format="rgb24").to_ndarray()[::stride, ::stride]
    if rgb.shape[0] == 0 or rgb.shape[1] == 0:
        return ()
    pixels = rgb.shape[0] * rgb.shape[1]
    channels = _to_hsv(rgb.astype(np.float32) / 255.0)
    return tuple(
        float(count) / pixels
        for channel in channels
        for count in np.bincount(
            np.clip((channel * bins).astype(np.int32), 0, bins - 1).reshape(-1), minlength=bins
        )
    )


def _to_hsv(rgb: "np.ndarray") -> tuple["np.ndarray", "np.ndarray", "np.ndarray"]:
    """`[0, 1]` RGB 평면을 `[0, 1)` H·S·V 세 평면으로. 표준 원뿔 변환이다.

    색상(H)을 따로 두는 것이 이 척도의 핵심이다. 조명이 오르내리면 V 만 움직이고 H 는
    거의 그대로이므로, 같은 화면의 밝기 변화가 새 keyframe 을 부르지 않는다.
    """
    red, green, blue = rgb[:, :, 0], rgb[:, :, 1], rgb[:, :, 2]
    value = rgb.max(axis=2)
    chroma = value - rgb.min(axis=2)
    saturation = np.divide(chroma, value, out=np.zeros_like(chroma), where=value > 0)

    hue = np.zeros_like(chroma)
    colored = chroma > 0
    for peak, offset, (left, right) in (
        (red, 0.0, (green, blue)),
        (green, 2.0, (blue, red)),
        (blue, 4.0, (red, green)),
    ):
        # 최대 채널이 무엇인지로 60도 구간을 고른다. 앞선 구간이 이미 칠한 자리는
        # 덮지 않는다 — 세 채널이 같은 회색 픽셀에서 순서에 따라 값이 갈리지 않게 한다.
        sector = colored & (value == peak) & (hue == 0.0)
        hue[sector] = (offset + (left - right)[sector] / chroma[sector]) % 6.0
    return hue / 6.0, saturation, value


def _encode_jpeg(frame: "av.VideoFrame", target: Path, qscale: int) -> None:
    """프레임 하나를 JPEG 으로 쓴다. **다운스케일하지 않는다.**

    품질은 `codec_context.qmin/qmax` 로 준다. `stream.options` 의 `q:v`·`qscale` 은
    ffmpeg **CLI** 의 옵션이라 libavcodec 에 닿지 않는다 — 넘겨도 조용히 무시되고
    파일 크기가 기본값 그대로다(실측 확인). 옵션을 준 줄 알고 넘어가면 작은 글자
    OCR 이 근거 없이 나빠진다.
    """
    with av.open(str(target), "w", format=_JPEG_CONTAINER_FORMAT) as out:
        stream = out.add_stream("mjpeg", rate=1)
        stream.width = frame.width
        stream.height = frame.height
        stream.pix_fmt = _JPEG_PIX_FMT
        stream.codec_context.color_range = ColorRange.JPEG
        stream.codec_context.thread_count = _JPEG_ENCODER_THREADS
        stream.codec_context.qmin = qscale
        stream.codec_context.qmax = qscale
        converted = frame.reformat(format=_JPEG_PIX_FMT, dst_color_range=ColorRange.JPEG)
        for packet in stream.encode(converted):
            out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)
