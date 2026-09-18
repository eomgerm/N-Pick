"""외부 GPU 실측의 기록·합격 판정 회귀. 실제 모델 성능을 검증하지 않는다."""

import json
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from npick_worker import korean_tokens
from npick_worker.vlm_metadata import benchmark, transformers_backend
from npick_worker.vlm_metadata.config import DEFAULT_CONFIG_PATH, get_default_config


def test_summary_does_not_pass_an_incomplete_run() -> None:
    row = {"inputs": [1, 2], "elapsedSeconds": 1.0}
    result = {"scenes": [row] * 9, "rejected": []}
    assert benchmark.summarize(result, 10)["smokePassed"] is False
    result["scenes"].append(row)
    assert benchmark.summarize(result, 10)["smokePassed"] is True
    result["scenes"][0] = {"inputs": [1], "elapsedSeconds": 1.0}
    assert benchmark.summarize(result, 10)["smokePassed"] is False


def test_summary_counts_normalized_values_without_failing_the_run() -> None:
    """정규화는 유효한 출력이다. 세되 합격 판정을 뒤집지 않는다 (S15P21A501-93)."""
    clean = {"inputs": [1, 2], "elapsedSeconds": 1.0}
    normalized = {
        **clean,
        "normalizations": ["caption", "tag_candidates"],
        "notations": ["caption"],
    }
    result = {"scenes": [*[clean] * 9, normalized], "rejected": []}

    summary = benchmark.summarize(result, 10)

    assert summary["normalizedScenes"] == 1
    assert summary["normalizedValues"] == 1
    assert summary["reshapedScenes"] == 1
    assert summary["reshapedValues"] == 1
    assert summary["smokePassed"] is True


def test_summary_separates_an_empty_screen_from_a_model_that_ignores_the_contract() -> None:
    """빈 화면만 있는 후보를 "계약을 안 따른다" 로 읽지 않는다 (S15P21A501-93 리뷰).

    2026-09-16 빈 화면 실측이 낸 모양이다 — 장면마다 `caption`·`scene_type` 두 자리가
    옮겨지지만 모델은 계약의 `null` 로 답했다. 후보를 가르는 값은 `normalizedValues` 고,
    그 값이 이런 장면에서 오르면 후보 비교표가 읽히지 않는다.
    """
    blank = {
        "inputs": [1, 2],
        "elapsedSeconds": 1.0,
        "normalizations": ["caption", "scene_type"],
        "notations": [],
    }
    result = {"scenes": [blank] * 10, "rejected": []}

    summary = benchmark.summarize(result, 10)

    assert summary["normalizedScenes"] == 0
    assert summary["normalizedValues"] == 0
    assert summary["reshapedScenes"] == 10
    assert summary["reshapedValues"] == 20
    assert summary["smokePassed"] is True


def test_summary_reads_a_record_from_before_normalization() -> None:
    """`normalizations` 가 없는 옛 기록을 0 으로 읽는다. 요약이 터지면 비교표가 사라진다."""
    result = {"scenes": [{"inputs": [1, 2], "elapsedSeconds": 1.0}], "rejected": []}

    summary = benchmark.summarize(result, 10)

    assert summary["normalizedScenes"] == 0
    assert summary["normalizedValues"] == 0
    assert summary["reshapedScenes"] == 0
    assert summary["reshapedValues"] == 0


def test_summary_reads_a_record_from_before_the_notation_split() -> None:
    """갈래를 모르는 기록은 전부 '모양' 으로 센다 (S15P21A501-93 리뷰).

    모르는 것을 '표기' 로 세면 없는 신호를 만들어 낸다. `normalizedValues` 는 사람을
    부르는 값이라, 틀리는 방향은 부르지 않는 쪽이어야 한다.
    """
    row = {"inputs": [1, 2], "elapsedSeconds": 1.0, "normalizations": ["caption"]}
    result = {"scenes": [row], "rejected": []}

    summary = benchmark.summarize(result, 10)

    assert summary["normalizedValues"] == 0
    assert summary["reshapedValues"] == 1


def test_summary_includes_failed_scene_latency() -> None:
    result = {
        "scenes": [{"inputs": [1, 2], "elapsedSeconds": 1}],
        "rejected": [{"elapsedSeconds": 120}],
    }
    summary = benchmark.summarize(result, 10)
    assert summary["attemptedMeanSeconds"] == 60.5
    assert summary["attemptedP95Seconds"] == 120


@pytest.mark.parametrize("load_fails", [False, True])
def test_benchmark_preserves_run_identity_and_load_failures(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    load_fails: bool,
) -> None:
    frames = tmp_path / "frames"
    for n in range(10):
        scene = frames / f"s{n:04d}"
        scene.mkdir(parents=True)
        for timestamp in (1000, 2000):
            (scene / f"kf-{timestamp:09d}.jpg").write_bytes(b"fake")
    options: dict[str, Any] = {}
    cuda = SimpleNamespace(
        is_available=lambda: True,
        is_bf16_supported=lambda: True,
        get_device_properties=lambda _: SimpleNamespace(total_memory=48 * 1024**3),
        set_per_process_memory_fraction=lambda fraction, device: options.update(fraction=fraction),
        get_device_name=lambda _: "fake GPU",
        synchronize=lambda: None,
        reset_peak_memory_stats=lambda: None,
        max_memory_allocated=lambda: 123,
        max_memory_reserved=lambda: 456,
    )
    monkeypatch.setitem(
        sys.modules,
        "torch",
        SimpleNamespace(
            cuda=cuda,
            manual_seed=lambda _: None,
            __version__="fake",
            version=SimpleNamespace(cuda="fake"),
        ),
    )
    sha = "a" * 40
    monkeypatch.setitem(
        sys.modules,
        "huggingface_hub",
        SimpleNamespace(
            HfApi=lambda: SimpleNamespace(model_info=lambda *a, **kw: SimpleNamespace(sha=sha)),
        ),
    )
    monkeypatch.setattr(benchmark, "_command", lambda _: "fake")
    monkeypatch.setattr(benchmark, "version", lambda _: "fake")
    monkeypatch.setattr(korean_tokens, "tokenizer_version", lambda: "fake")

    class Client:
        name = "fake"
        version = "fake"
        device = "cuda"
        model_version = "test/model@" + sha

        def warm_up(self) -> None:
            if load_fails:
                raise RuntimeError("load failed")

        def _ensure_loaded(self) -> tuple[Any, Any]:
            return SimpleNamespace(chat_template="test"), SimpleNamespace(
                dtype="torch.bfloat16",
                config=SimpleNamespace(_attn_implementation="sdpa"),
                generation_config=SimpleNamespace(to_dict=lambda: {}),
            )

        def describe(self, *args: Any) -> str:
            return '{"shot_type":{"value":"unknown","confidence":0,"evidence":[]}}'

    def build(*args: Any, **kwargs: Any) -> Client:
        options.update(kwargs)
        return Client()

    monkeypatch.setattr(benchmark, "build_client", build)
    cache_dir = tmp_path / "shared-model-cache"
    monkeypatch.setattr(benchmark, "get_settings", lambda: SimpleNamespace(vlm_model_dir=cache_dir))
    out = tmp_path / "result"
    args = [str(frames), "--model", "test/model", "--out", str(out), "--memory-budget-gib", "20"]
    if load_fails:
        with pytest.raises(RuntimeError, match="load failed"):
            benchmark.main(args)
    else:
        assert benchmark.main(args) == 0
        summary = json.loads((out / "summary.json").read_text(encoding="utf-8"))
        assert summary["smokePassed"] is True
        assert summary["qualityVerdict"] == "not_evaluated"
    run = json.loads((out / "run.json").read_text(encoding="utf-8"))
    assert run["status"] == ("failed" if load_fails else "succeeded")
    assert run["modelRevision"] == sha
    assert options["revision"] == sha
    assert options["model_dir"] == cache_dir
    assert options["dtype"] == "bfloat16"
    # thinking 은 생성자가 아니라 **실행에 쓰는 설정**에 있다 (S15P21A501-214). 기록한
    # config.toml 을 본다 — report.main 이 다시 읽는 것이 이 파일이다.
    assert "enable_thinking = false" in (out / "config.toml").read_text(encoding="utf-8")
    assert options["fraction"] == pytest.approx(20 / 48)
    inputs = json.loads((out / "inputs.json").read_text(encoding="utf-8"))
    assert len(inputs) == 20
    assert all(len(row["sha256"]) == 64 for row in inputs)
    assert (out / "schema.json").exists()
    with pytest.raises(FileExistsError):
        benchmark.main(args)


def test_adapter_forwards_thinking_to_chat_template(monkeypatch: pytest.MonkeyPatch) -> None:
    """`_generate` 는 받은 값을 그대로 템플릿에 싣는다.

    **이것만으로는 부족하다.** 이 배관은 처음부터 맞았고, 그런데도 운영 경로는 thinking 이
    켜진 채로 돌았다 — 값을 넘기는 쪽이 넘기지 않았기 때문이다. 배선은
    `test_vlm_metadata.py` 의 `test_describe_takes_thinking_from_call_params` 가 건다.
    """

    class StopTemplateError(Exception):
        pass

    options: dict[str, Any] = {}

    def template(*args: Any, **kwargs: Any) -> str:
        options.update(kwargs)
        raise StopTemplateError

    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace())
    monkeypatch.setitem(sys.modules, "PIL", SimpleNamespace(Image=object()))
    with pytest.raises(StopTemplateError):
        transformers_backend._generate(
            SimpleNamespace(apply_chat_template=template),
            None,
            [],
            [],
            get_default_config().call,
            enable_thinking=False,
        )
    assert options["enable_thinking"] is False


def test_benchmark_writes_thinking_into_the_config_it_runs() -> None:
    """기록한 config 와 실제로 돌린 config 가 같아야 한다.

    `report.main` 은 out 디렉터리의 `config.toml` 을 다시 읽어 돌린다. 원본을 그대로
    베끼면 `--thinking` 이 조용히 무시되고, 기록만 바뀐 실행이 남는다.
    """
    source = DEFAULT_CONFIG_PATH.read_text(encoding="utf-8")
    assert "enable_thinking = true" in benchmark._with_thinking(source, True)
    assert "enable_thinking = false" in benchmark._with_thinking(source, False)
    # 키가 없는 설정에도 넣는다. 없으면 갈아끼울 곳이 없어 조용히 원본대로 돈다.
    v1 = DEFAULT_CONFIG_PATH.with_name("vlm_metadata.v1.toml").read_text(encoding="utf-8")
    assert "enable_thinking" not in v1
    assert "enable_thinking = true" in benchmark._with_thinking(v1, True)
