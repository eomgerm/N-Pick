"""장면 텍스트를 벡터로 바꾼다. 이 단계의 본체다."""

from collections.abc import Sequence
from typing import Final

from npick_worker.text_embedding.config import TextEmbeddingConfig, get_default_config
from npick_worker.text_embedding.encoder import TextEncoder, finalize_vector
from npick_worker.text_embedding.models import SceneEmbedding, SceneText, TextEmbeddingResult

#: 캡션 덩어리와 대사 뭉치를 나누는 구분자. 설정으로 빼지 않는다 — 바꾸면 전체 재색인인데
#: 바꿀 이유가 없고, 버전 붙는 설정에 두면 실수로 움직였을 때 대가가 크다.
SECTION_SEPARATOR: Final[str] = "\n"

#: 대사 줄끼리 잇는 구분자. 위와 같은 이유로 상수다.
DIALOGUE_SEPARATOR: Final[str] = " "


def compose_text(scene: SceneText, config: TextEmbeddingConfig) -> str:
    """캡션과 대사를 벡터 하나의 입력으로 합친다 (FRD §11 결정 표, `docs/frd.md:609`).

    캡션과 대사 뭉치를 `SECTION_SEPARATOR` 로 나누고 대사 줄끼리는 `DIALOGUE_SEPARATOR`
    로 잇는다. 둘을 같은 구분자로 이으면 "설명" 과 "발화" 의 경계가 사라지는데, 그
    경계는 모델이 문장 구조를 읽는 단서다.

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
    dialogue = DIALOGUE_SEPARATOR.join(line.strip() for line in scene.dialogue if line.strip())
    if dialogue:
        sections.append(dialogue)
    if not sections:
        return ""
    return config.document_prefix + SECTION_SEPARATOR.join(sections)


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
        ValueError: 모델 차원이 설정과 다르거나, 못 쓰는 벡터(0·NaN)가 나왔거나,
            `scene_index` 가 중복이거나, 인코더가 입력과 다른 수의 벡터를 돌려줬다.
            전부 부분 결과를 반납하면 안 되는 상태다 — 잘못된 차원은 BE 의 INSERT 를
            통째로 실패시키고, 중복 index 는 한 행을 두 번 덮어쓴다. **이것들은 "이
            장면에 텍스트가 없다"(`skipped`)와 다른 사실이다.** 그쪽은 정상 입력이고
            이쪽은 설정이나 모델이 잘못됐다는 신호라, 조용히 넘기면 원인을 찾을 수 없다.
    """
    settings = config if config is not None else get_default_config()
    model = encoder if encoder is not None else _default_encoder()

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
        # 여기서 걸리지 않으면 아래 zip 이 짧은 쪽에 맞춰 잘리거나(strict 가 없다면)
        # 어느 장면이 밀렸는지 알 수 없는 예외가 난다.
        msg = f"인코더가 입력과 다른 수의 벡터를 돌려줬다: {len(texts)} → {len(vectors)}"
        raise ValueError(msg)

    results: list[SceneEmbedding] = []
    for scene, text, vector in zip(embedded, texts, vectors, strict=True):
        results.append(
            SceneEmbedding(
                scene_index=scene.scene_index,
                vector=finalize_vector(
                    vector, dimension=settings.dimension, normalize=settings.normalize
                ),
                source_text=text,
            )
        )

    return TextEmbeddingResult(
        scenes=tuple(results),
        skipped=tuple(skipped),
        config_version=settings.version_id,
        engine=model.name,
        engine_version=model.version,
        model_version=model.model_version,
        dimension=settings.dimension,
    )


def _default_encoder() -> TextEncoder:
    """기본 backend. 지연 임포트는 `sentence_transformers_backend._load` 안에 있다.

    **프로세스가 공유하는 인스턴스를 받는다**(`shared_encoder`). 잡마다 새로 만들면
    수 GB 가중치를 잡마다 읽는다 — `ocr` 의 `shared_engine` 과 같은 판단이다.
    """
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    return shared_encoder()
