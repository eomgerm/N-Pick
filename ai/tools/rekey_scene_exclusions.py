"""Re-key active `exclude_scene` rules to the current query normalization.

An `exclude_scene` rule is matched exactly on `(query_fingerprint, normalized_query,
normalization_version, normalized_filters_json)` (BE `ActiveSceneExclusionQueryAdapter`).
All four were copied from the search that produced the report. When the normaliser
changes, `normalization_version` changes for **every** query, so every stored rule stops
matching. This tool recomputes the three query-side columns from the rule's source query
text (`search_rule.source_feedback_id -> feedback -> search_result -> search_execution
.query_text`) with the normaliser in this checkout. Filters are left as stored — they are
normalised by BE, not by the AI side.

The fingerprint is BE's `NormalizedSearch.computeFingerprint`, ported byte for byte:
SHA-256 over length-prefixed UTF-8 strings (4-byte big-endian lengths) — query, version,
filter count, then each filter key, its value count and its values. Keys are in Java
`String.compareTo` order (UTF-16 code units). Before writing anything the tool
**recomputes every stored fingerprint from the stored columns** and stops if one does not
match, so a divergence from BE's hashing cannot silently produce dead rules.

    cd ai && uv run python tools/rekey_scene_exclusions.py --in rules.csv --out rekey.csv

Idempotent: a rule already on the current version with the same key is not written.
The procedure (export/apply SQL) is in `ai/docs/index-token-backfill.md`.
"""

import argparse
import csv
import hashlib
import json
import sys
from pathlib import Path

from npick_worker.query_normalization import normalize


def _update(digest: "hashlib._Hash", value: str) -> None:
    encoded = value.encode("utf-8")
    _count(digest, len(encoded))
    digest.update(encoded)


def _count(digest: "hashlib._Hash", count: int) -> None:
    digest.update(count.to_bytes(4, "big", signed=True))


def _java_order(value: str) -> bytes:
    """Java `String.compareTo` compares UTF-16 code units; big-endian bytes sort the same."""
    return value.encode("utf-16-be")


def fingerprint(normalized_query: str, filters: dict[str, list[str]], version: str) -> str:
    """BE `NormalizedSearch.computeFingerprint`. `filters` must already be BE-normalised."""
    digest = hashlib.sha256()
    _update(digest, normalized_query)
    _update(digest, version)
    _count(digest, len(filters))
    for key in sorted(filters, key=_java_order):
        values = filters[key]
        _update(digest, key)
        _count(digest, len(values))
        for value in values:
            _update(digest, value)
    return digest.hexdigest()


def rekey(source: Path, out: Path) -> list[dict[str, str]]:
    with source.open(encoding="utf-8", newline="") as rows:
        rules = list(csv.DictReader(rows))

    # Stop before writing if the port disagrees with any stored fingerprint.
    for rule in rules:
        stored = fingerprint(
            rule["normalized_query"],
            json.loads(rule["normalized_filters_json"]),
            rule["normalization_version"],
        )
        if stored != rule["query_fingerprint"]:
            msg = f"fingerprint port disagrees with BE for rule {rule['search_rule_id']}"
            raise SystemExit(msg)

    report = []
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, quoting=csv.QUOTE_ALL)
        writer.writerow(
            [
                "search_rule_id",
                "old_fingerprint",
                "normalized_query",
                "normalization_version",
                "query_fingerprint",
            ]
        )
        for rule in rules:
            try:
                normalized = normalize(rule["query_text"])
            except ValueError as error:
                # 내용어가 없는 질의. 원래 규칙도 만들어질 수 없었던 입력이라 손대지 않고 알린다.
                print(f"rule={rule['search_rule_id']} skipped: {error}", file=sys.stderr)
                continue
            new_fingerprint = fingerprint(
                normalized.normalized_query,
                json.loads(rule["normalized_filters_json"]),
                normalized.normalization_version,
            )
            changed = new_fingerprint != rule["query_fingerprint"]
            if changed:
                writer.writerow(
                    [
                        rule["search_rule_id"],
                        rule["query_fingerprint"],
                        normalized.normalized_query,
                        normalized.normalization_version,
                        new_fingerprint,
                    ]
                )
            report.append(
                {
                    "search_rule_id": rule["search_rule_id"],
                    "old_query": rule["normalized_query"],
                    "new_query": normalized.normalized_query,
                    "old_version": rule["normalization_version"],
                    "changed": str(changed),
                }
            )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--in",
        dest="source",
        type=Path,
        required=True,
        help="CSV exported with the query in ai/docs/index-token-backfill.md",
    )
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = rekey(args.source, args.out)
    for row in report:
        print(
            f"rule={row['search_rule_id']} changed={row['changed']} "
            f"{row['old_query']!r} ({row['old_version']}) -> {row['new_query']!r}",
            file=sys.stderr,
        )
    changed = sum(row["changed"] == "True" for row in report)
    print(f"rules={len(report)} changed={changed} unchanged={len(report) - changed}")


if __name__ == "__main__":
    main()
