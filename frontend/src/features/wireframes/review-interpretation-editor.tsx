'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type DragEvent, type KeyboardEvent, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  defaultType,
  deriveEdits,
  deriveParseRules,
  describeEdits,
  seedChips,
  EDITABLE_AXES,
  type Chip,
  type EditableAxis,
} from '@/features/wireframes/interpretation-edit';
import {
  createParsePatchCandidate,
  discardParsePatchCandidate,
  parseRuleErrorMessage,
  resolutionAxisLabels,
  validateParseRuleBody,
  type ParseRuleCandidateBody,
} from '@/features/wireframes/review-parse-rule-api';
import { parseResolution } from '@/features/wireframes/reviewer-resolution-state';
import styles from '@/features/wireframes/review-interpretation-editor.module.css';
import { ApiClientError } from '@/lib/api/error';

interface ParseInterpretationEditorProps {
  feedbackId: string;
  parsedQueryJson: string | null;
}

const MAX_PARSE_RULE_DRAFTS = 10;
const MAX_CHIP_VALUE_LENGTH = 20;

/** 문의 당시 해석 스냅샷을 칩으로 씨딩한다. 없거나 깨졌으면 편집할 것이 없다. */
function seedFromJson(json: string | null): Chip[] | null {
  if (!json) return null;
  try {
    return seedChips(parseResolution(json));
  } catch {
    return null;
  }
}

/**
 * add 규칙의 적용 조건이 될 대표 값. 사건명 우선, 없으면 인물·기관, 없으면 장소·시설의 첫 값.
 * 추론값(`inferred`)은 리졸버가 재실행되면 흔들릴 수 있어 후보에서 뺀다 — 명시(`explicit*`) 칩만 본다.
 */
function computeGuard(original: Chip[]): { axis: EditableAxis; value: string } | null {
  for (const axis of ['incident_names', 'entities', 'locations'] as const) {
    const chip = original.find((item) => item.axis === axis && item.origin?.startsWith('explicit'));
    if (chip) return { axis, value: chip.value };
  }
  return null;
}

/**
 * 규칙 내용으로부터 결정론적 멱등성 키를 만든다. 같은 내용은 매번 같은 키가 되어, 중간 실패 뒤
 * 재시도해도 이미 만든 후보를 새로 만들지 않고 서버가 기존 후보를 그대로 돌려준다 — 재시도마다 후보가
 * 쌓여 신고당 후보 상한(SRCH_409_204)에 닿는 것을 막는다.
 */
async function ruleIdempotencyKey(
  feedbackId: string,
  body: ParseRuleCandidateBody,
): Promise<string> {
  const bytes = new TextEncoder().encode(`${feedbackId}:${JSON.stringify(body)}`);
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes);
  const hex = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join(
    '',
  );
  // 서버 상한은 64자 (docs/contracts/web-api.md); 'parse:' 접두 + 48자 해시로 여유 있게 맞춘다.
  return `parse:${hex.slice(0, 48)}`;
}

export function ParseInterpretationEditor({
  feedbackId,
  parsedQueryJson,
}: ParseInterpretationEditorProps) {
  const queryClient = useQueryClient();
  const seeded = seedFromJson(parsedQueryJson);
  // 원본 스냅샷은 다시 세팅하지 않는 값이라 state 로 보존한다 (렌더 중 ref.current 를 읽지 않기 위함).
  const [originalChips] = useState<Chip[]>(() => seeded ?? []);
  const [chips, setChips] = useState<Chip[]>(() => seeded ?? []);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dropTargetAxis, setDropTargetAxis] = useState<EditableAxis | null>(null);
  const newChipCounter = useRef(0);
  // 서버에 이 화면이 보낸 후보가 남아 있을 수 있는지. POST 를 보내는 순간 참이 된다 — 서버가 저장했는데
  // 응답만 유실되거나 여러 건 중 일부만 저장되고 실패해도, 그 뒤 편집하면 이전 후보를 폐기하기 위함이다.
  // 일괄 폐기 DELETE 는 멱등이라 실제로 남은 후보가 없어도 무해하다. 폐기가 성공했을 때만 거짓으로 돌린다.
  const mayHaveServerCandidates = useRef(false);
  const suppressBlur = useRef(false);

  const guard = computeGuard(originalChips);
  const edits = deriveEdits(originalChips, chips);
  const descriptions = describeEdits(edits);
  const descriptionGroups = [
    { key: '추가', tone: 'add' },
    { key: '삭제', tone: 'remove' },
    { key: '수정', tone: 'edit' },
  ].map((group) => ({
    ...group,
    items: descriptions.filter((description) => description.key === group.key),
  }));
  const rules = deriveParseRules(edits, guard);
  const draftCount = rules.length + chips.filter((chip) => chip.isNew && !chip.value.trim()).length;

  // 검증(재검색)이 저장 진행 중에 끼어들지 않도록, 검증 패널이 useIsMutating 으로 감시할 키를 단다.
  const save = useMutation({
    mutationKey: ['parse-patch-save', feedbackId],
    mutationFn: async () => {
      if (rules.length === 0) {
        throw new ApiClientError('api', 0, {
          code: 'CLIENT_PARSE_RULE_EMPTY',
          message: '담을 교정이 없습니다.',
        });
      }
      if (rules.length > MAX_PARSE_RULE_DRAFTS) {
        throw new ApiClientError('api', 0, {
          code: 'CLIENT_PARSE_RULE_LIMIT',
          message: '교정은 한 번에 10개까지 담을 수 있습니다.',
        });
      }
      if (
        edits.some((edit) => {
          // remove 는 값을 새로 넣지 않고, move 는 원본 칩을 옮길 뿐이라 길이 상한을 검사하지 않는다.
          // add·edit 의 새로 입력한 값만 검사한다 (입력창 maxLength 로 이미 20자 이하로 제한된다).
          if (edit.kind === 'remove' || edit.kind === 'move') return false;
          const value = edit.kind === 'edit' ? edit.to : edit.value;
          return value.length > MAX_CHIP_VALUE_LENGTH;
        })
      ) {
        throw new ApiClientError('api', 0, {
          code: 'CLIENT_PARSE_RULE_VALUE_TOO_LONG',
          message: '교정 값은 20자 이하여야 합니다.',
        });
      }
      // 편집마다 독립 후보로 보낸다. 한 규칙에 조건을 합치면 서버가 모든 조건을 동시에 요구해, 한쪽 값만
      // 가진 해석에는 교정이 걸리지 않는다 (FRD F-11 "독립 규칙은 함께 적용"). 일부만 저장된 채 멈추지
      // 않도록 모든 본문을 POST 전에 먼저 검증한다.
      for (const rule of rules) {
        const problem = validateParseRuleBody(rule);
        if (problem) {
          throw new ApiClientError('api', 0, {
            code: 'CLIENT_PARSE_RULE_INVALID',
            message: problem,
          });
        }
      }
      // 순서대로 보낸다. 중간에 실패하면 다시 눌러 전부 재전송하면 된다 — 이미 저장된 규칙은 같은 결정적
      // 멱등성 키라 서버가 기존 후보를 돌려주어 중복이 생기지 않는다.
      for (const rule of rules) {
        const key = await ruleIdempotencyKey(feedbackId, rule);
        // 응답을 받기 전에 표시한다 — 요청이 서버에 닿았는지는 응답이 유실되면 알 수 없다.
        mayHaveServerCandidates.current = true;
        await createParsePatchCandidate(feedbackId, rule, key);
      }
      return rules.length;
    },
    onSuccess: () => {
      // 담은 뒤에도 편집 상태를 그대로 둔다 — '바뀌는 점'이 남아 무엇을 담았는지 계속 보인다.
      // 재클릭해도 규칙별 결정적 idempotency key 로 서버가 같은 후보를 돌려주어 중복이 생기지 않는다.
      queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] });
    },
  });

  const discard = useMutation({
    mutationFn: () => discardParsePatchCandidate(feedbackId),
    onSuccess: () => {
      mayHaveServerCandidates.current = false;
    },
  });

  // 저장 POST 는 누른 시점의 규칙을 담는다. 진행 중에 칩을 바꾸면 성공 뒤 '담았어요'가 실제로 담지 않은
  // 내용을 가리키고, 이전 후보 폐기가 아직 끝나지 않은 POST 와 엇갈린다 — 그동안 편집을 잠근다.
  const locked = save.isPending;

  // 저장을 시도한 뒤(성공·일부 성공·응답 유실 포함) 다시 편집하면 서버에 남았을 수 있는 이전 후보를
  // 모두 폐기한다. 그러지 않으면 다음 저장이 새 후보를 더 만들고(내용이 달라 멱등성 키도 달라짐), 화면에서
  // 이미 고친 이전 후보가 새 후보와 함께 검증·확정된다. 폐기가 실패하면 표시가 남아 다음 편집에서도 다시
  // 폐기하고, 저장은 폐기가 성공할 때까지 막힌다.
  function resetAfterSave() {
    if (!mayHaveServerCandidates.current) return;
    save.reset();
    discard.mutate();
  }

  if (!seeded) {
    return (
      <section className={styles.card}>
        <p className={styles.emptyNotice}>문의 당시 검색 해석이 없어 칩 편집을 할 수 없습니다.</p>
      </section>
    );
  }

  function startEdit(chip: Chip) {
    if (locked) return;
    // 이전 Escape 취소가 blur 를 못 만나 남겨둔 억제 플래그가 다음 편집까지 새지 않게 한다.
    suppressBlur.current = false;
    setEditingId(chip.id);
    setEditDraft(chip.value);
  }

  function removeChip(id: string) {
    if (locked) return;
    // 값을 담은 적 없는 새 빈 칩을 지우는 건 교정 변경이 아니다. 실제 칩(값 있음/기존)만 폐기를 유발한다.
    const target = chips.find((chip) => chip.id === id);
    if (target && !(target.isNew && !target.value.trim())) resetAfterSave();
    setChips((prev) => prev.filter((chip) => chip.id !== id));
    if (editingId === id) setEditingId(null);
  }

  function addChip(axis: EditableAxis) {
    if (locked) return;
    // 빈 칩을 추가하는 것만으로는 교정 내용이 바뀌지 않는다 — 실제 값을 확정(commitEdit)할 때만 이전 후보를 폐기한다.
    newChipCounter.current += 1;
    const chip: Chip = {
      id: `new-${newChipCounter.current}`,
      axis,
      value: '',
      isNew: true,
      type: defaultType(axis),
    };
    setChips((prev) => [...prev, chip]);
    setEditingId(chip.id);
    setEditDraft('');
  }

  function commitEdit() {
    const id = editingId;
    if (id === null) return;
    const value = editDraft.trim();
    const chip = chips.find((item) => item.id === id);
    // 값이 그대로면(포커스만 옮김·재확정) 교정 내용이 안 바뀐 것이므로 이전 후보를 폐기하지 않는다.
    // 새 빈 칩을 빈 값으로 확정하는 것도 실제로는 아무것도 담기지 않으므로 폐기 대상이 아니다.
    const changed = chip ? chip.value !== value : false;
    if (changed) resetAfterSave();
    setChips((prev) =>
      value
        ? prev.map((item) => (item.id === id ? { ...item, value } : item))
        : prev.filter((item) => item.id !== id),
    );
    setEditingId(null);
  }

  function cancelEdit() {
    const id = editingId;
    setEditingId(null);
    if (id === null) return;
    // 새로 만들었지만 확정한 적 없는 빈 칩은 취소 시 사라진다. 기존 칩은 값을 그대로 둔다.
    setChips((prev) => {
      const chip = prev.find((item) => item.id === id);
      return chip && !chip.value.trim() ? prev.filter((item) => item.id !== id) : prev;
    });
  }

  function onEditKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter') {
      event.preventDefault();
      commitEdit();
    } else if (event.key === 'Escape') {
      event.preventDefault();
      suppressBlur.current = true;
      cancelEdit();
    }
  }

  function onEditBlur() {
    if (suppressBlur.current) {
      suppressBlur.current = false;
      return;
    }
    commitEdit();
  }

  function onChipDragStart(event: DragEvent<HTMLSpanElement>, chip: Chip) {
    if (locked) {
      event.preventDefault();
      return;
    }
    event.dataTransfer.setData('text/plain', chip.id);
    event.dataTransfer.effectAllowed = 'move';
    setDraggingId(chip.id);
  }

  function onChipDragEnd() {
    setDraggingId(null);
    setDropTargetAxis(null);
  }

  function onRowDragOver(event: DragEvent<HTMLDivElement>, axis: EditableAxis) {
    event.preventDefault();
    setDropTargetAxis(axis);
  }

  function onRowDrop(event: DragEvent<HTMLDivElement>, axis: EditableAxis) {
    event.preventDefault();
    const id = event.dataTransfer.getData('text/plain');
    setDropTargetAxis(null);
    setDraggingId(null);
    if (locked) return;
    // 같은 축에 다시 떨구거나 대상이 없으면 실제 변경이 없다 — 폐기하지 않는다.
    const target = chips.find((chip) => chip.id === id);
    if (!target || target.axis === axis) return;
    resetAfterSave();
    setChips((prev) =>
      prev.map((chip) => (chip.id === id ? { ...chip, axis, type: defaultType(axis) } : chip)),
    );
  }

  const serverMessage = save.isError ? parseRuleErrorMessage(save.error) : null;

  return (
    <div className={styles.wrap}>
      <section className={styles.card}>
        <h3 className={styles.heading}>검색 해석 교정</h3>
        <p className={styles.hint}>칩을 클릭해 값 수정 · ×로 삭제 · 다른 항목으로 끌어 이동</p>

        <div className={styles.rows}>
          {EDITABLE_AXES.map((axis) => (
            <div
              className={`${styles.row} ${dropTargetAxis === axis ? styles.dropTarget : ''}`}
              key={axis}
              onDragOver={(event) => onRowDragOver(event, axis)}
              onDrop={(event) => onRowDrop(event, axis)}
            >
              <div className={styles.axis}>{resolutionAxisLabels[axis]}</div>
              <div className={styles.chips}>
                {chips
                  .filter((chip) => chip.axis === axis)
                  .map((chip) =>
                    editingId === chip.id ? (
                      <input
                        aria-label={`${resolutionAxisLabels[axis]} 값 수정`}
                        autoFocus
                        className={styles.chipInput}
                        key={chip.id}
                        maxLength={MAX_CHIP_VALUE_LENGTH}
                        onBlur={onEditBlur}
                        onChange={(event) => setEditDraft(event.target.value)}
                        onKeyDown={onEditKeyDown}
                        value={editDraft}
                      />
                    ) : (
                      <span
                        aria-disabled={locked || undefined}
                        className={`${styles.chip} ${draggingId === chip.id ? styles.dragging : ''}`}
                        draggable={!locked}
                        key={chip.id}
                        onClick={() => startEdit(chip)}
                        onDragEnd={onChipDragEnd}
                        onDragStart={(event) => onChipDragStart(event, chip)}
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            startEdit(chip);
                          }
                        }}
                        role="button"
                        tabIndex={0}
                      >
                        {chip.value}
                        <button
                          aria-label={`'${chip.value}' 삭제`}
                          className={styles.chipRemove}
                          disabled={locked}
                          onClick={(event) => {
                            event.stopPropagation();
                            removeChip(chip.id);
                          }}
                          type="button"
                        >
                          ×
                        </button>
                      </span>
                    ),
                  )}
                <button
                  aria-label={`${resolutionAxisLabels[axis]}에 항목 추가`}
                  className={styles.addButton}
                  disabled={locked || !guard || draftCount >= MAX_PARSE_RULE_DRAFTS}
                  onClick={() => addChip(axis)}
                  title={
                    guard ? undefined : '대표 항목(사건명·인물·장소)이 없어 추가할 수 없습니다.'
                  }
                  type="button"
                >
                  +
                </button>
              </div>
            </div>
          ))}
        </div>

        {!guard ? (
          <p className={styles.guardNotice}>
            사건명·인물·장소 항목이 없어 새 항목을 추가할 수 없습니다.
          </p>
        ) : null}
      </section>

      <section
        className={`${styles.card} ${descriptions.length === 0 ? styles.changeCardDisabled : ''}`}
      >
        <div className={styles.changeHeading}>바뀌는 점</div>
        {descriptions.length > 0 ? (
          <div className={styles.changeGrid}>
            {descriptionGroups.map((group) => (
              <section className={styles.changeGroup} data-kind={group.tone} key={group.key}>
                <h4 className={styles.changeGroupHeading}>
                  {group.key} <span>{group.items.length}</span>
                </h4>
                {group.items.length > 0 ? (
                  <ul className={styles.changeList}>
                    {group.items.map((item, index) => (
                      <li key={index}>{item.text}</li>
                    ))}
                  </ul>
                ) : (
                  <p className={styles.changeGroupEmpty}>없음</p>
                )}
              </section>
            ))}
          </div>
        ) : (
          <p className={styles.empty}>칩을 수정하면 바뀌는 점이 여기 표시됩니다.</p>
        )}

        <button
          className={styles.primaryButton}
          disabled={
            save.isPending || discard.isPending || discard.isError || descriptions.length === 0
          }
          onClick={() => save.mutate()}
          type="button"
        >
          {discard.isPending ? '이전 교정 폐기 중…' : save.isPending ? '담는 중…' : '교정 담기'}
        </button>

        {discard.isError ? (
          <div className={styles.errorGroup}>
            <p className={styles.hint} role="alert">
              이전 교정을 폐기하지 못했어요. 잠시 후 다시 시도해 주세요.
            </p>
            <button
              className={styles.secondaryButton}
              disabled={discard.isPending}
              onClick={() => discard.mutate()}
              type="button"
            >
              폐기 다시 시도
            </button>
          </div>
        ) : null}

        {save.isError ? (
          <div className={styles.errorGroup}>
            {serverMessage ? (
              <p className={styles.hint} role="alert">
                {serverMessage}
              </p>
            ) : (
              <ApiErrorNotice error={save.error} />
            )}
          </div>
        ) : null}
        {save.isSuccess ? (
          <p className={styles.success} role="status">
            {`${save.data}개 교정을 담았어요. 아래에서 검증하고 확정하세요.`}
          </p>
        ) : null}
      </section>
    </div>
  );
}
