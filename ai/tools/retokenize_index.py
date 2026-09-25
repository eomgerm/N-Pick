"""Re-create stored BM25 index tokens with the current `korean_tokens` rules.

The index token columns are made by the worker, never by BE, and they must be made by
the same rules the query side uses (`korean_tokens.py`). When those rules change
(S15P21A501-320: irregular verbs/adjectives are no longer dropped), rows already stored
keep the old tokens until they are rebuilt. This tool rebuilds them **in-process** — it
imports `korean_tokens` directly instead of calling `POST /query/tokenize`, which caps
each text at 200 characters.

The worker has no DB driver on purpose, so the tool reads and writes CSV. `psql \\copy`
exports `(id, source text, current tokens)`, this tool writes `(id, old tokens, new
tokens)` for the rows whose tokens actually change, and `psql` applies them with
`UPDATE ... FROM` a temp table. The full procedure is in
`ai/docs/index-token-backfill.md`.

Each column is rebuilt exactly as its producer builds it — `" ".join(index_tokens(text))`:

    scene.caption_tokens       <- scene.caption          (vlm_metadata, `Caption.tokens_text`)
    scene.transcript_tokens    <- scene.transcript_text  (scene_transcript_mapping; BE joins
                                  the linked segments with one space, the worker tokenises
                                  that same string)
    ocr_observation.tokens     <- ocr_observation.raw_text (ocr, `OcrObservation.tokens_text`)

    cd ai && uv run python tools/retokenize_index.py \\
        --in scene_caption.csv --out-dir patch/ --name scene_caption

Idempotent: a row whose rebuilt tokens equal the stored ones is not written, so a second
run over a fresh export produces no patch rows. The old tokens travel with each patch
row so the UPDATE can skip a row that changed after the export.
"""

import argparse
import csv
import sys
from pathlib import Path

from npick_worker.korean_tokens import index_tokens, tokenizer_version

#: Source columns are free text; the default CSV field limit (128 KiB) is below what a
#: long transcript can reach.
csv.field_size_limit(sys.maxsize)


def retokenize(source: Path, out_dir: Path, name: str, batch_size: int) -> dict[str, int | str]:
    """Write `<name>.NNNN.csv` patch files and return the counts."""
    out_dir.mkdir(parents=True, exist_ok=True)
    counts: dict[str, int | str] = {"tokenizer": tokenizer_version()}
    total = changed = batches = 0
    writer = None
    handle = None
    with source.open(encoding="utf-8", newline="") as rows:
        for row in csv.DictReader(rows):
            total += 1
            new_tokens = " ".join(index_tokens(row["text"]))
            old_tokens = row["tokens"]
            if new_tokens == old_tokens:
                continue
            if changed % batch_size == 0:
                if handle is not None:
                    handle.close()
                path = out_dir / f"{name}.{batches:04d}.csv"
                handle = path.open("w", encoding="utf-8", newline="")
                # QUOTE_ALL: in COPY csv an unquoted empty field is NULL, a quoted one is ''.
                # A text with no content words has '' tokens, which is not the same as NULL.
                writer = csv.writer(handle, quoting=csv.QUOTE_ALL)
                writer.writerow(["id", "old_tokens", "new_tokens"])
                batches += 1
            assert writer is not None
            writer.writerow([row["id"], old_tokens, new_tokens])
            changed += 1
    if handle is not None:
        handle.close()
    counts.update(rows=total, changed=changed, unchanged=total - changed, batches=batches)
    return counts


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--in",
        dest="source",
        type=Path,
        required=True,
        help="CSV with header id,text,tokens (psql \\copy ... CSV HEADER)",
    )
    parser.add_argument("--out-dir", type=Path, required=True)
    parser.add_argument("--name", required=True, help="patch file prefix, e.g. scene_caption")
    parser.add_argument(
        "--batch-size",
        type=int,
        default=5000,
        help="rows per patch file; each file is applied in its own transaction",
    )
    args = parser.parse_args()
    counts = retokenize(args.source, args.out_dir, args.name, args.batch_size)
    print(" ".join(f"{key}={value}" for key, value in counts.items()))


if __name__ == "__main__":
    main()
