"""`tools/retokenize_index.py`·`tools/rekey_scene_exclusions.py` (S15P21A501-320).

두 도구는 운영 DB 의 저장 값을 덮어쓴다. 어긋나면 증상이 조용하다 — 토큰이 틀리면
검색이 0 건, 지문이 틀리면 장면 제외가 안 걸린다 — 그래서 모양을 여기서 고정한다.
"""

import csv
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))

from rekey_scene_exclusions import fingerprint, rekey
from retokenize_index import retokenize

from npick_worker import korean_tokens
from npick_worker.query_normalization import normalize


def _write(path: Path, header: list[str], rows: list[list[str]]) -> Path:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)
    return path


def test_fingerprint_matches_the_backend_value_object() -> None:
    """BE `NormalizedSearch.of(...).fingerprint()` 를 jshell 로 돌려 얻은 값이다.

    두 번째는 필터 키 정렬(Java `compareTo`: 대문자 < 한글)·값 목록까지 먹인 경우다.
    """
    version = "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0:encpos2"
    assert fingerprint("경기 축구", {}, version) == (
        "02d49191cd006a775308189e1165bcf554f25a6b649786610f298b94e05cb6a7"
    )
    filters = {"tag": ["부산", "서울"], "Zeta": ["b"], "가": ["x"]}
    assert fingerprint("걷 모습 사람", filters, "v") == (
        "20297fd1773a7017082faf5dac7ff2d456ea1745ca5afcc23532a0250f2a85a3"
    )


def test_retokenize_writes_only_changed_rows_and_keeps_empty_tokens_distinct(
    tmp_path: Path,
) -> None:
    current = " ".join(korean_tokens.index_tokens("비 내리는 길"))
    source = _write(
        tmp_path / "in.csv",
        ["id", "text", "tokens"],
        [
            ["1", "사람들이 걷는 모습", "사람/nng 모습/nng"],  # 옛 규칙 — 걷 이 빠졌다
            ["2", "비 내리는 길", current],  # 이미 새 규칙과 같다
            ["3", "...", "stale/nng"],  # 내용어가 없다 — '' 가 되어야 한다
        ],
    )
    counts = retokenize(source, tmp_path / "out", "scene_caption", batch_size=1)
    assert counts["rows"] == 3
    assert counts["changed"] == 2
    assert counts["batches"] == 2

    patch = sorted((tmp_path / "out").glob("scene_caption.*.csv"))
    lines = [line for path in patch for line in path.read_text(encoding="utf-8").splitlines()[1:]]
    assert lines == [
        '"1","사람들이 걷는 모습","사람/nng 모습/nng","사람/nng 걷/vv 모습/nng"',
        # COPY csv 에서 따옴표 없는 빈 칸은 NULL 이다. 빈 토큰은 '' 로 남아야 한다
        '"3","...","stale/nng",""',
    ]


def test_retokenize_removes_stale_patch_files_of_the_same_name(tmp_path: Path) -> None:
    """다시 돌려 배치가 줄거나 0 이 되면 앞 회차의 끝쪽 파일이 적용 루프에 섞이면 안 된다."""
    out = tmp_path / "out"
    rows = [[str(i), "사람들이 걷는 모습", "사람/nng 모습/nng"] for i in range(3)]
    retokenize(_write(tmp_path / "a.csv", ["id", "text", "tokens"], rows), out, "cap", 1)
    other = out / "cap_other.0000.csv"
    other.write_text("keep", encoding="utf-8")  # 다른 이름의 패치는 건드리지 않는다
    assert len(list(out.glob("cap.*.csv"))) == 3

    current = " ".join(korean_tokens.index_tokens("사람들이 걷는 모습"))
    counts = retokenize(
        _write(
            tmp_path / "b.csv", ["id", "text", "tokens"], [["1", "사람들이 걷는 모습", current]]
        ),
        out,
        "cap",
        1,
    )
    assert counts["changed"] == 0
    assert list(out.glob("cap.*.csv")) == []
    assert other.exists()


def test_backfill_doc_updates_only_rows_whose_source_text_is_unchanged() -> None:
    """적용 SQL 은 문서에만 있다.

    원문 비교가 빠지면 옛 토큰이 같은 다른 원문 행에 새 토큰이 들어간다.

    옛 규칙에서 「사람들이 걷는 모습」과 「사람들이 듣는 모습」은 둘 다 `사람/nng 모습/nng` 다.
    """
    doc = (Path(__file__).resolve().parents[1] / "docs" / "index-token-backfill.md").read_text(
        encoding="utf-8"
    )
    assert "CREATE TEMP TABLE patch (id bigint PRIMARY KEY, text text NOT NULL" in doc
    updates = [chunk.split('"; done')[0] for chunk in doc.split('apply_patch "$f" "')[1:]]
    assert len(updates) == 3
    for update, source in zip(
        updates, ["s.caption", "s.transcript_text", "o.raw_text"], strict=True
    ):
        assert f"{source} = p.text" in update
        assert "= p.old_tokens" in update


def test_rekey_refuses_when_the_stored_fingerprint_cannot_be_reproduced(tmp_path: Path) -> None:
    header = [
        "search_rule_id",
        "query_text",
        "normalized_query",
        "normalized_filters_json",
        "normalization_version",
        "query_fingerprint",
    ]
    source = _write(
        tmp_path / "rules.csv", header, [["1", "축구경기", "경기 축구", "{}", "v", "0" * 64]]
    )
    with pytest.raises(SystemExit):
        rekey(source, tmp_path / "out.csv")
    assert not (tmp_path / "out.csv").exists()


def test_rekey_recomputes_query_version_and_fingerprint(tmp_path: Path) -> None:
    header = [
        "search_rule_id",
        "query_text",
        "normalized_query",
        "normalized_filters_json",
        "normalization_version",
        "query_fingerprint",
    ]
    old_version = "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0:encpos2"
    old = fingerprint("모습 사람", {}, old_version)
    source = _write(
        tmp_path / "rules.csv",
        header,
        [["7", "사람들이 걷는 모습", "모습 사람", "{}", old_version, old]],
    )
    rekey(source, tmp_path / "out.csv")

    expected = normalize("사람들이 걷는 모습")
    with (tmp_path / "out.csv").open(encoding="utf-8", newline="") as handle:
        (row,) = list(csv.DictReader(handle))
    assert row["normalized_query"] == expected.normalized_query == "걷 모습 사람"
    assert row["normalization_version"] == expected.normalization_version
    assert row["query_fingerprint"] == fingerprint(
        expected.normalized_query, {}, expected.normalization_version
    )
    assert row["old_fingerprint"] == old

    # 다시 돌리면 쓸 것이 없다
    rerun = _write(
        tmp_path / "rules2.csv",
        header,
        [
            [
                "7",
                "사람들이 걷는 모습",
                row["normalized_query"],
                "{}",
                row["normalization_version"],
                row["query_fingerprint"],
            ]
        ],
    )
    report = rekey(rerun, tmp_path / "out2.csv")
    assert [r["status"] for r in report] == ["unchanged"]


def test_rekey_skips_rules_whose_source_query_was_garbled(tmp_path: Path) -> None:
    """U+FFFD 가 든 원 질의는 다시 정규화해도 그 쓰레기 글자의 지문이 나올 뿐이다.

    재키하면 아무도 칠 수 없는 질의에 규칙이 되살아난다. 쓰지 않고 `skipped` 로 알린다.
    """
    version = "query-norm/v1:b0d96c0c:kiwi0.23.2:model0.23.0"
    garbled = "ȸ\ufffd\ufffd ǥ"
    header = [
        "search_rule_id",
        "query_text",
        "normalized_query",
        "normalized_filters_json",
        "normalization_version",
        "query_fingerprint",
    ]
    source = _write(
        tmp_path / "rules.csv",
        header,
        [["9", garbled, "ǥ ȸ", "{}", version, fingerprint("ǥ ȸ", {}, version)]],
    )
    report = rekey(source, tmp_path / "out.csv")

    assert [r["status"] for r in report] == ["skipped (query_text contains U+FFFD)"]
    assert (tmp_path / "out.csv").read_text(encoding="utf-8").count("\n") == 1  # 머리글만
