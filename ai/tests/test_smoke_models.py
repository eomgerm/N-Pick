"""로컬 GPU/CPU 실행 환경 확인. `uv run pytest -m smoke` 로만 실행된다.

임포트를 함수 안에 두는 이유: 마커 deselect 는 collection 이후에 일어나므로
모듈 레벨에서 torch 를 임포트하면 기본 테스트 실행도 수 초 느려진다.
"""

import sys

import pytest

pytestmark = pytest.mark.smoke

_GPU_GROUP_HINT = "gpu 그룹 미설치: uv sync --group gpu --group cu130|cu128"


def test_torch_is_cuda_build() -> None:
    """CPU 전용 휠이 잘못 설치된 상황을 잡는다.

    PyPI 의 win_amd64 torch 휠은 CPU 전용이라 CUDA 가 조용히 비활성화된다.
    pyproject.toml 의 [tool.uv.sources] 가 cu130 인덱스를 가리켜야 한다.
    """
    torch = pytest.importorskip("torch", reason=_GPU_GROUP_HINT)
    if sys.platform == "darwin":
        pytest.skip("macOS 는 CUDA 휠이 존재하지 않는다 (MPS 휠로 해석됨)")

    assert torch.version.cuda is not None, (
        f"CPU 전용 torch 휠이 설치되었다 (torch={torch.__version__}). "
        "pyproject.toml 의 [[tool.uv.index]] pytorch-cu130 설정을 확인하라."
    )


def test_torch_cuda_matmul() -> None:
    """GPU 에서 실제 연산이 수행되는지 확인한다."""
    torch = pytest.importorskip("torch", reason=_GPU_GROUP_HINT)
    if not torch.cuda.is_available():
        pytest.skip(f"CUDA 미사용 환경 (torch.version.cuda={torch.version.cuda})")

    props = torch.cuda.get_device_properties(0)
    print(f"\nGPU: {props.name}, VRAM {props.total_memory // (1024 * 1024)}MiB")

    x = torch.randn(1024, 1024, device="cuda")
    result = (x @ x).sum().item()
    torch.cuda.synchronize()

    assert result == result, "행렬곱 결과가 NaN 이다"


def test_faster_whisper_tiny_cpu() -> None:
    """ASR 모델을 실제로 로드하고 인코더를 돌린다.

    16kHz 무음을 in-process 로 만들어 넣으므로 오디오 픽스처도, ffmpeg 도 필요 없다.
    """
    np = pytest.importorskip("numpy", reason=_GPU_GROUP_HINT)
    faster_whisper = pytest.importorskip("faster_whisper", reason=_GPU_GROUP_HINT)

    model = faster_whisper.WhisperModel("tiny", device="cpu", compute_type="int8")
    audio = np.zeros(16_000 * 2, dtype=np.float32)  # 2초 무음

    segments, info = model.transcribe(audio, beam_size=1)

    assert 1.9 <= info.duration <= 2.1
    # 언어 판별은 encoder forward pass 다 → 인코더가 실제로 돌았음이 보장된다.
    assert isinstance(info.language, str)
    assert 0.0 <= info.language_probability <= 1.0
    # 제너레이터를 소진해 디코더까지 실행한다. 무음이라 세그먼트 수는 단언하지 않는다.
    assert isinstance(list(segments), list)


def test_scene_embedding_with_the_selected_model() -> None:
    """선정 모델로 샘플 scene 에서 **버전 정보를 포함한** embedding 을 산출한다.

    S15P21A501-100 의 완료 조건이자 S15P21A501-175 의 마지막 확인 항목이다.

    모델은 `settings.embedding_model` 의 **확정 기본값**을 쓴다(S15P21A501-175).
    환경 변수로 덮으면 그 모델로 돈다.

        uv run pytest -m smoke -k scene_embedding
    """
    pytest.importorskip("sentence_transformers", reason=_GPU_GROUP_HINT)

    from npick_worker.settings import get_settings
    from npick_worker.text_embedding import SceneText, embed_scenes, get_default_config
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    model_id = get_settings().embedding_model
    if not model_id:
        pytest.skip("NPICK_AI_EMBEDDING_MODEL 이 빈 값으로 덮여 있다")

    config = get_default_config()
    encoder = shared_encoder()
    scenes = [
        SceneText(
            scene_index=0,
            caption="광화문 광장에 모인 집회 참가자들",
            dialogue=("현장에 나가 있는 기자 연결합니다",),
        ),
        # 텍스트가 없는 장면. 벡터를 만들지 않는 쪽이 정상이다.
        SceneText(scene_index=1, caption="", dialogue=()),
    ]

    result = embed_scenes(scenes, encoder=encoder, config=config)

    print(f"\nmodel={result.model_version} engine={result.engine}/{result.engine_version}")
    print(f"config={result.config_version} dim={result.dimension}")

    # 산출 — 텍스트가 있는 장면에만 벡터가 있다.
    assert [scene.scene_index for scene in result.scenes] == [0]
    assert result.skipped == (1,)
    # 차원이 scene.embedding vector(1024) 와 맞는가. 여기서 어긋나면 저장이 실패한다.
    assert len(result.scenes[0].vector) == config.dimension
    # 정규화됐는가. pgvector 코사인 검색의 전제다.
    assert sum(value * value for value in result.scenes[0].vector) == pytest.approx(1.0, abs=1e-5)
    # 버전 정보 — FR-PRC-061. 어느 값도 비어 있으면 재현할 수 없다.
    assert result.model_version.startswith(model_id + "@")
    assert result.engine == "sentence-transformers"
    assert result.config_version.startswith("text-embedding/v1:")


def test_query_embedding_shares_the_index_vector_space() -> None:
    """선정 모델로 샘플 질의에서 **버전 정보를 포함한** 벡터를 산출한다.

    S15P21A501-164 의 완료 조건이다. "차원이 S15P21A501-100 과 일치" 를 설정값이 아니라
    **같은 모델로 실제 장면을 임베딩해서** 확인한다 — 두 경로가 같은 설정을 읽는 것은
    `tests/test_query_embedding.py` 가 이미 보므로, 여기서 볼 것은 그 값이 실물과
    맞는가다.

        uv run pytest -m smoke -k query_embedding
    """
    pytest.importorskip("sentence_transformers", reason=_GPU_GROUP_HINT)

    from npick_worker.query_embedding import embed_query
    from npick_worker.settings import get_settings
    from npick_worker.text_embedding import SceneText, embed_scenes
    from npick_worker.text_embedding import get_default_config as scene_config
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    model_id = get_settings().embedding_model
    if not model_id:
        pytest.skip("NPICK_AI_EMBEDDING_MODEL 이 빈 값으로 덮여 있다")

    # **같은 인코더다.** 색인과 질의가 가중치 한 벌을 공유한다.
    encoder = shared_encoder()
    config = scene_config()

    query = embed_query("광화문 집회 현장 스케치", encoder=encoder, config=config)
    scene = embed_scenes(
        [
            SceneText(
                scene_index=0,
                caption="광화문 광장에 모인 집회 참가자들",
                dialogue=("현장에 나가 있는 기자 연결합니다",),
            )
        ],
        encoder=encoder,
        config=config,
    )

    print(f"\nmodel={query.model_version} config={config.version_id}")
    print(f"embedded={query.source_text!r}")

    # 버전 정보 — 비어 있으면 어느 가중치로 만든 벡터인지 말할 수 없다.
    assert query.model_version.startswith(model_id + "@")
    assert query.model_version == scene.model_version

    # 접두가 실제로 붙었는가. 모델 카드가 요구하는 값이다.
    assert query.source_text.startswith(get_settings().embedding_query_prefix)

    # 차원이 색인 측과 같은가. 여기서 갈리면 코사인 비교 자체가 불가능하다.
    assert len(query.vector) == len(scene.scenes[0].vector) == config.dimension
    assert sum(value * value for value in query.vector) == pytest.approx(1.0, abs=1e-5)

    # 같은 공간에 있는가. 관련 있는 장면이라 코사인이 0 보다 확실히 크다.
    # 임계값을 높게 잡지 않는다 — 품질 측정은 `eval/` 과 S15P21A501-175 의 몫이다.
    cosine = sum(q * s for q, s in zip(query.vector, scene.scenes[0].vector, strict=True))
    print(f"cosine={cosine:.4f}")
    assert cosine > 0.3, "질의와 관련 장면의 코사인이 너무 낮다. 접두나 모델을 의심한다"
