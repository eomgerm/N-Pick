"""외부 GPU 실측의 기록·합격 판정 회귀. 실제 모델 성능을 검증하지 않는다."""

import json
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest

from npick_worker import korean_tokens
from npick_worker.vlm_metadata import benchmark, transformers_backend
from npick_worker.vlm_metadata.config import get_default_config


def test_summary_does_not_pass_an_incomplete_run() -> None:
    row = {"inputs": [1, 2], "elapsedSeconds": 1.0}
    result = {"scenes": [row] * 9, "rejected": []}
    assert benchmark.summarize(result, 10)["smokePassed"] is False
    result["scenes"].append(row)
    assert benchmark.summarize(result, 10)["smokePassed"] is True
    result["scenes"][0] = {"inputs": [1], "elapsedSeconds": 1.0}
    assert benchmark.summarize(result, 10)["smokePassed"] is False


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
    assert options["dtype"] == "bfloat16"
    assert options["enable_thinking"] is False
    assert options["fraction"] == pytest.approx(20 / 48)
    inputs = json.loads((out / "inputs.json").read_text(encoding="utf-8"))
    assert len(inputs) == 20
    assert all(len(row["sha256"]) == 64 for row in inputs)
    assert (out / "schema.json").exists()
    with pytest.raises(FileExistsError):
        benchmark.main(args)


def test_adapter_forwards_non_thinking_to_chat_template(monkeypatch: pytest.MonkeyPatch) -> None:
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
