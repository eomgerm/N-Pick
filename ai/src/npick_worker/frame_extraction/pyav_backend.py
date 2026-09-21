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
from npick_worker.frame_extraction.selector import SceneMeasurement, ScoredFrame
from npick_worker.media_errors import MediaUnreadableError
from npick_worker.timecode import frame_number_from_pts, frames_to_ms

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
    ) -> Mapping[int, SceneMeasurement]:
        """후보를 재서 `scene_index → SceneMeasurement` 를 돌려준다.

        **후보 평면을 scene 하나치만 들고 있는다.** 변화량(`content_val`)은 픽셀을 맞대어
        보는 값이라 두 프레임이 동시에 있어야 하는데, 영상 전체의 후보 평면을 쥐면 장면
        수에 비례해 메모리가 는다. scene 은 시간순이고 후보도 프레임 순으로 오므로, 다음
        scene 의 후보가 나타나는 순간 직전 scene 의 쌍 거리를 다 재고 평면을 버린다.
        """
        owner: dict[int, int] = {
            frame_number: request.scene_index
            for request in requests
            for slot in request.slots
            for frame_number in slot.frame_numbers
        }
        measured: dict[int, dict[int, ScoredFrame]] = {
            request.scene_index: {} for request in requests
        }
        changes: dict[int, dict[tuple[int, int], float]] = {
            request.scene_index: {} for request in requests
        }
        if not owner:
            return {index: SceneMeasurement(frames={}, changes={}) for index in measured}

        planes: dict[int, tuple[np.ndarray, ...]] = {}
        open_scene: int | None = None

        with av.open(str(video_path)) as container:
            frame_rate = _profile(container).frame_rate
            for frame_number, frame in _decode_until(container, max(owner), frame_rate):
                scene_index = owner.get(frame_number)
                if scene_index is None:
                    continue
                if scene_index != open_scene:
                    if open_scene is not None:
                        changes[open_scene] = _pairwise_change(planes)
                    planes = {}
                    open_scene = scene_index
                score, luma_std = _score(frame, cfg.score_stride)
                measured[scene_index][frame_number] = ScoredFrame(
                    frame_number=frame_number,
                    timestamp_ms=frames_to_ms(frame_number, frame_rate),
                    score=score,
                    luma_std=luma_std,
                )
                planes[frame_number] = _hsv_planes(frame, cfg.change_stride)
            if open_scene is not None:
                changes[open_scene] = _pairwise_change(planes)

        return {
            index: SceneMeasurement(frames=frames, changes=changes[index])
            for index, frames in measured.items()
        }

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
            # `measure` 와 같은 번호를 매겨야 `targets` 의 키가 같은 프레임을 가리킨다.
            frame_rate = _profile(container).frame_rate
            for frame_number, frame in _decode_until(container, max(targets), frame_rate):
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

    프레임레이트로 `guessed_rate` 를 쓴다. PySceneDetect 의 `VideoStreamAv.frame_rate`
    가 그 값이기 때문이다(scenedetect 0.7.1, `backends/pyav.py`) — 다른 값을 쓰면
    `scene_detection` 이 만든 ms 를 프레임 번호로 되돌릴 때 어긋나고, keyframe 이 자기
    scene 밖의 프레임을 가리킬 수 있다.

    **`average_rate` 는 쓸 수 없다.** 그것은 컨테이너가 적어 둔 값이 아니라 대개
    `프레임수 / duration` 으로 유도되는 값이라, 마지막 프레임의 지속시간을 duration 에
    넣지 않는 먹서를 만나면 위로 밀린다. 짧은 영상일수록 크게 밀린다 — 분모가 작아서다.
    배포에서 죽은 26.8초 클립이 정확히 그 경우다 — 간격 804 개 중 777 개가 정확히
    1/30초이고 나머지도 한 곳(1584 ticks)을 빼면 ±1 tick 인데, `average_rate` 만
    30.0355 였다(S15P21A501-259). ffmpeg 을 거쳐 다시 써진 파일은 duration 이 정리되므로
    두 값이 같아지고, 그래서 주로 폰·카메라가 직접 쓴 원본에서 드러난다.
    """
    if not container.streams.video:
        msg = "비디오 스트림이 없는 파일이다"
        raise MediaUnreadableError(msg)
    stream = container.streams.video[0]
    if stream.guessed_rate is None or float(stream.guessed_rate) <= 0:
        msg = "프레임레이트를 읽을 수 없다"
        raise MediaUnreadableError(msg)
    width = stream.codec_context.width
    height = stream.codec_context.height
    if width <= 0 or height <= 0:
        msg = f"해상도를 읽을 수 없다: {width}x{height}"
        raise MediaUnreadableError(msg)
    return MediaProfile(frame_rate=float(stream.guessed_rate), width=width, height=height)


def _decode_until(
    container: "av.container.InputContainer", last_frame: int, frame_rate: float
) -> Iterator[tuple[int, "av.VideoFrame"]]:
    """0번부터 `last_frame` 까지 순차 디코드한다.

    탐색(seek)하지 않는다. 탐색은 키프레임 정렬 때문에 원하는 프레임을 놓칠 수 있고
    (`scene_detection/report.py` 의 같은 판단), 무엇보다 컨테이너의 GOP 구조에 따라
    결과가 달라지면 멱등성이 깨진다.

    프레임 번호는 **PTS 에서 온다**(`timecode.frame_number_from_pts`). 상류가 그렇게
    세기 때문이다 — `SceneManager` 는 `video.position` 으로 scene 경계를 적는다.

    **`last_frame` 을 가진 프레임을 전부 내보내고 멈춘다.** 번호가 겹칠 수 있는데,
    호출부 둘(`measure`·`write`)이 번호를 키로 하는 dict 에 last-wins 로 쌓으므로 한쪽만
    첫 번째에서 멈추면 점수를 잰 프레임과 저장된 JPEG 이 예외 없이 달라진다.
    """
    stream = container.streams.video[0]
    start_time = stream.start_time or 0
    for frame in container.decode(stream):
        if frame.pts is None or frame.time_base is None:
            msg = "프레임의 표시 시각(PTS)을 읽을 수 없다"
            raise MediaUnreadableError(msg)
        frame_number = frame_number_from_pts(
            frame.pts,
            frame.time_base,
            frame_rate,
            start_time=start_time,
            stream_time_base=stream.time_base,
        )
        if frame_number > last_frame:
            return
        yield frame_number, frame


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


def _hsv_planes(frame: "av.VideoFrame", stride: int) -> tuple["np.ndarray", ...]:
    """변화량 계산용 H·S·V 평면. OpenCV 8bit HSV 와 같은 눈금이다.

    `cv2.cvtColor(..., COLOR_BGR2HSV)` 의 8bit 범위가 H `0~179`, S·V `0~255` 다. 그
    눈금을 그대로 쓰는 이유는 `content_val` 을 scene 분할과 **같은 자**로 재기 위해서다
    (FRD F-03 의 척도 통일 권고). 눈금이 다르면 두 단계의 임계값을 비교할 수 없다.

    ffmpeg 에 HSV 픽셀 형식이 없어서 RGB 로 받아 여기서 변환한다
    (`reformat(format="hsv")` 는 `not a pixel format` 으로 죽는다). 변환을 numpy 로 하는
    편이 `cv2` 를 이 패키지의 의존성으로 끌어오는 것보다 낫다 — CPU 전용 fleet 이 frame
    extraction 만 돌릴 때 `scene_detection` 의 무거운 의존성을 따라오게 하지 않는 것이
    이 모듈의 전제다. `scenedetect` 의 `content_val` 과 대조해 소수점 둘째 자리까지
    일치함을 확인했다(`ai/docs/frame-extraction.md` §3.1).

    `stride` 로 솎아 읽는다. **평균을 흐리지 않는다** — 솎기는 평균 절대차의 불편추정이라
    전체 픽셀로 잰 값과 같은 눈금에 있다. 이웃을 평균 내는 축소와는 다르다.
    """
    rgb = frame.reformat(format="rgb24").to_ndarray()[::stride, ::stride].astype(np.float32)
    return _to_hsv_scaled(rgb / 255.0)


def _pairwise_change(
    planes: Mapping[int, tuple["np.ndarray", ...]],
) -> dict[tuple[int, int], float]:
    """scene 안 후보 쌍 전부의 `content_val`."""
    numbers = sorted(planes)
    return {
        (left, right): _content_val(planes[left], planes[right])
        for index, left in enumerate(numbers)
        for right in numbers[index + 1 :]
    }


def _content_val(left: Sequence["np.ndarray"], right: Sequence["np.ndarray"]) -> float:
    """PySceneDetect `ContentDetector` 의 `content_val`.

    H·S·V 채널별 평균 절대차의 산술평균이다. `ContentDetector` 의 기본 가중치가
    `(hue, saturation, luma, edges) = (1, 1, 1, 0)` 이므로 세 채널을 같은 무게로 평균한
    것과 같다. `luma_only = false` 인 현재 scene 분할 설정에 대응한다.

    그래서 이 값은 `scene_detection` 의 `content.threshold`(기본 27.0)와 **같은 자 위에
    있다.** 다만 재는 거리가 다르다 — 그쪽은 인접 프레임(t, t-1)이고 여기는 수 초 떨어진
    두 자리다. 같은 눈금이라고 같은 임계값이 맞는 것은 아니므로 값은 따로 실측한다.
    """
    return float(sum(np.abs(a - b).mean() for a, b in zip(left, right, strict=True)) / 3.0)


def _to_hsv_scaled(rgb: "np.ndarray") -> tuple["np.ndarray", "np.ndarray", "np.ndarray"]:
    """`[0, 1]` RGB 평면을 OpenCV 8bit 눈금의 H·S·V 평면으로.

    표준 원뿔 변환에 `cv2` 의 범위를 입힌 것이다 — H `0~179`, S·V `0~255`. 그 눈금이라야
    `_content_val` 이 scene 분할의 `content_val` 과 같은 값을 낸다.
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
    return hue / 6.0 * 180.0, saturation * 255.0, value * 255.0


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
