"""장면 텍스트를 벡터로 바꾼다. 이 단계의 본체다."""

import math
from collections.abc import Sequence

from npick_worker.text_embedding.config import TextEmbeddingConfig, get_default_config
from npick_worker.text_embedding.encoder import TextEncoder
from npick_worker.text_embedding.models import SceneEmbedding, SceneText, TextEmbeddingResult


def compose_text(scene: SceneText, config: TextEmbeddingConfig) -> str:
    """캡션과 대사를 벡터 하나의 입력으로 합친다 (FRD §11.4).

    캡션과 대사 뭉치를 `section_separator` 로 나누고 대사 줄끼리는
    `dialogue_separator` 로 잇는다. 둘을 같은 구분자로 이으면 "설명" 과 "발화" 의
    경계가 사라지는데, 그 경계는 모델이 문장 구조를 읽는 단서다.

    공백만 있는 값은 없는 것으로 본다. 그대로 두면 공백을 임베딩하게 되고, 그러면
    "텍스트가 없는 장면" 이 벡터를 갖게 된다.

    Returns:
        합친 문자열. 쓸 텍스트가 없으면 빈 문자열이다 — 호출부가 이 값으로 건너뛸지를
        정한다.
    """
    sections: list[str] = []
    caption = scene.caption.strip()
    if caption:
        sections.append(caption)
    dialogue = config.dialogue_separator.join(
        line.strip() for line in scene.dialogue if line.strip()
    )
    if dialogue:
        sections.append(dialogue)
    if not sections:
        return ""
    return config.document_prefix + config.section_separator.join(sections)


def embed_scenes(
    scenes: Sequence[SceneText],
    *,
    encoder: TextEncoder | None = None,
    config: TextEmbeddingConfig | None = None,
) -> TextEmbeddingResult:
    """장면마다 dense 벡터 하나를 만든다.

    Args:
        scenes: 임베딩할 장면. 상류 산출물의 순서를 유지한다.
        encoder: 없으면 기본 backend 를 만든다. 테스트와 모델 비교에서 갈아 끼운다.
        config: 없으면 동봉 기본 설정.

    Returns:
        벡터를 만든 장면과 건너뛴 장면, 그리고 재현 정보. **텍스트가 없는 장면은
        결과에 벡터가 없다** — 빈 문자열을 임베딩하면 모든 빈 장면이 서로 최근접이
        되어 보조 채널이 오염된다. `scene.embedding` 이 nullable 인 이유가 그것이다.

    Raises:
        ValueError: 모델 차원이 설정과 다르거나, 0 벡터가 나왔거나, `scene_index` 가
            중복이다. 셋 다 부분 결과를 반납하면 안 되는 상태다 — 잘못된 차원은 BE 의
            INSERT 를 통째로 실패시키고, 중복 index 는 한 행을 두 번 덮어쓴다.
    """
    settings = config if config is not None else get_default_config()
    model = encoder if encoder is not None else _default_encoder(settings)

    seen: set[int] = set()
    for scene in scenes:
        if scene.scene_index in seen:
            msg = f"scene_index 가 중복이다: {scene.scene_index}"
            raise ValueError(msg)
        seen.add(scene.scene_index)

    texts: list[str] = []
    embedded: list[SceneText] = []
    skipped: list[int] = []
    for scene in scenes:
        text = compose_text(scene, settings)
        if not text:
            skipped.append(scene.scene_index)
            continue
        texts.append(text)
        embedded.append(scene)

    # 빈 배치로 가중치를 깨우지 않는다. 장면이 0 개인 클립에서도 재현 정보는 남는다.
    vectors = model.encode(texts) if texts else ()
    if len(vectors) != len(texts):
        msg = f"인코더가 입력과 다른 수의 벡터를 돌려줬다: {len(texts)} → {len(vectors)}"
        raise ValueError(msg)

    results: list[SceneEmbedding] = []
    for scene, text, vector in zip(embedded, texts, vectors, strict=True):
        results.append(
            SceneEmbedding(
                scene_index=scene.scene_index,
                vector=_finalize(vector, settings),
                source_text=text,
            )
        )

    return TextEmbeddingResult(
        scenes=tuple(results),
        skipped=tuple(skipped),
        config_version=settings.version_id,
        adapter=model.name,
        adapter_version=model.version,
        model_version=model.model_version,
        dimension=settings.dimension,
    )


def _finalize(vector: Sequence[float], config: TextEmbeddingConfig) -> tuple[float, ...]:
    """차원을 확인하고 필요하면 L2 정규화한다.

    차원 검사를 여기서 하는 이유는 `TextEncoder.dimension` 을 믿지 않기 위해서다. 그
    값은 모델이 선언한 것이고, 실제로 나온 벡터와 다를 수 있다(차원을 잘라 쓰는
    Matryoshka 설정이 그렇다). 검사는 실제 값으로 한다.
    """
    if len(vector) != config.dimension:
        msg = (
            f"모델이 낸 벡터의 차원이 설정과 다르다: 설정 {config.dimension}, "
            f"모델 {len(vector)}. scene.embedding vector({config.dimension}) 과 "
            f"어긋나면 저장이 통째로 실패한다"
        )
        raise ValueError(msg)
    if not config.normalize:
        return tuple(float(value) for value in vector)
    norm = math.sqrt(sum(float(value) * float(value) for value in vector))
    if norm == 0.0:
        # pgvector 의 코사인 거리가 0 벡터에서 NaN 이 된다. 저장하면 그 장면이
        # 모든 질의에서 조용히 빠지고, 원인은 검색 결과가 아니라 벡터에 있다.
        msg = "모델이 0 벡터를 냈다. 정규화할 수 없고 코사인 거리가 정의되지 않는다"
        raise ValueError(msg)
    return tuple(float(value) / norm for value in vector)


def _default_encoder(config: TextEmbeddingConfig) -> TextEncoder:
    """기본 backend. 지연 임포트로 sentence-transformers 비용을 호출 시점까지 미룬다.

    **프로세스가 공유하는 인스턴스를 받는다**(`shared_encoder`). 잡마다 새로 만들면
    수 GB 가중치를 잡마다 읽는다 — `ocr` 의 `shared_engine` 과 같은 판단이다.
    """
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    return shared_encoder(config)
