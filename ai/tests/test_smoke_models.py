"""로컬 GPU/CPU 실행 환경 확인. `uv run pytest -m smoke` 로만 실행된다.

임포트를 함수 안에 두는 이유: 마커 deselect 는 collection 이후에 일어나므로
모듈 레벨에서 torch 를 임포트하면 기본 테스트 실행도 수 초 느려진다.
"""

import os
import sys

import pytest

pytestmark = pytest.mark.smoke

_GPU_GROUP_HINT = "gpu 그룹 미설치: uv sync --group gpu"


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

    모델 이름은 `NPICK_AI_EMBEDDING_MODEL` 이 준다 — 코드도 테스트도 모델을 고르지
    않는다(`text_embedding/config.py` 의 판단). 잠정 선정값으로 돌리려면:

        NPICK_AI_EMBEDDING_MODEL=dragonkue/snowflake-arctic-embed-l-v2.0-ko \
            uv run pytest -m smoke -k scene_embedding
    """
    pytest.importorskip("sentence_transformers", reason=_GPU_GROUP_HINT)

    from npick_worker.text_embedding import SceneText, embed_scenes, get_default_config
    from npick_worker.text_embedding.sentence_transformers_backend import shared_encoder

    if not os.environ.get("NPICK_AI_EMBEDDING_MODEL"):
        pytest.skip("NPICK_AI_EMBEDDING_MODEL 미설정")

    config = get_default_config()
    encoder = shared_encoder(config)
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

    print(f"\nmodel={result.model_version} adapter={result.adapter}/{result.adapter_version}")
    print(f"config={result.config_version} dim={result.dimension}")

    # 산출 — 텍스트가 있는 장면에만 벡터가 있다.
    assert [scene.scene_index for scene in result.scenes] == [0]
    assert result.skipped == (1,)
    # 차원이 scene.embedding vector(1024) 와 맞는가. 여기서 어긋나면 저장이 실패한다.
    assert len(result.scenes[0].vector) == config.dimension
    # 정규화됐는가. pgvector 코사인 검색의 전제다.
    assert sum(value * value for value in result.scenes[0].vector) == pytest.approx(1.0, abs=1e-5)
    # 버전 정보 — FR-PRC-061. 어느 값도 비어 있으면 재현할 수 없다.
    assert result.model_version.startswith(os.environ["NPICK_AI_EMBEDDING_MODEL"] + "@")
    assert result.adapter == "sentence-transformers"
    assert result.config_version.startswith("text-embedding/v1:")
