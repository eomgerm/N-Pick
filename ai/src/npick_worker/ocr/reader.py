"""keyframe 목록을 읽어 관측 목록을 만든다. 이 단계의 본체다."""

from collections.abc import Mapping, Sequence
from pathlib import Path

from npick_worker import korean_tokens
from npick_worker.ocr.config import OcrConfig, get_default_config
from npick_worker.ocr.engine import OcrEngine
from npick_worker.ocr.merge import OcrMergeConfig, get_merge_config
from npick_worker.ocr.models import KeyframeObservations, KeyframeRef, OcrResult
from npick_worker.ocr.postprocess import to_observations


def read_keyframes(
    keyframes: Sequence[KeyframeRef],
    image_paths: Mapping[str, Path],
    *,
    engine: OcrEngine | None = None,
    config: OcrConfig | None = None,
    merge_config: OcrMergeConfig | None = None,
) -> OcrResult:
    """keyframe 마다 화면 글자를 읽는다.

    Args:
        keyframes: 읽을 대상. 상류 `frame_extraction` 산출물의 순서를 유지한다.
        image_paths: `storage_key` → 로컬 파일. **이 단계는 파일을 내려받지 않는다** —
            바이트를 가져오는 일은 잡 레이어의 몫이고(`ai/AGENTS.md`: 단계는 순수
            함수), 여기서 하면 단계가 잡 API 를 알아야 한다.
        engine: 없으면 기본 backend 를 만든다. 테스트와 엔진 비교에서 갈아 끼운다.
        config: 없으면 동봉 기본 설정.

    Returns:
        keyframe 하나당 묶음 하나. 글자가 없는 프레임도 **빈 묶음으로 남긴다** —
        "읽었는데 없었다" 와 "읽지 않았다" 는 다르고, 뒤엣것은 이 단계의 결함이다.

    Raises:
        KeyError: `image_paths` 에 없는 `storage_key` 가 있다. 상류 산출물과 실제로
            받은 파일이 어긋난 것이므로 일부만 읽고 성공으로 반납하지 않는다.
    """
    settings = config if config is not None else get_default_config()
    ocr_engine = engine if engine is not None else _default_engine(settings)

    results: list[KeyframeObservations] = []
    for keyframe in keyframes:
        image_path = image_paths.get(keyframe.storage_key)
        if image_path is None:
            msg = f"keyframe 이미지를 받지 못했다: {keyframe.storage_key}"
            raise KeyError(msg)
        detections = ocr_engine.read(image_path)
        results.append(
            to_observations(keyframe, detections, min_confidence=settings.min_confidence)
        )

    return OcrResult(
        keyframes=tuple(results),
        config_version=settings.version_id,
        engine=ocr_engine.name,
        engine_version=ocr_engine.version,
        tokenizer=korean_tokens.tokenizer_version(),
        min_confidence=settings.min_confidence,
        merge_config=merge_config if merge_config is not None else get_merge_config(),
    )


def _default_engine(config: OcrConfig) -> OcrEngine:
    """기본 backend. 지연 임포트로 rapidocr 비용을 호출 시점까지 미룬다.

    **프로세스가 공유하는 인스턴스를 받는다**(`shared_engine`). 잡마다 새로 만들면
    ONNX 세션 생성 비용을 잡마다 내고, 그러면 기동 때 하는 워밍업이 실제로는 가중치
    내려받기만 앞당기게 된다.
    """
    from npick_worker.ocr.rapidocr_backend import shared_engine

    return shared_engine(config)
