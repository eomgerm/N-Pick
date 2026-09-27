'use client';

import { useIsMutating, useMutation, useQueryClient } from '@tanstack/react-query';
import { type DragEvent, type KeyboardEvent, useId, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  additionGuardOptions,
  defaultAdditionGuard,
  defaultType,
  deriveEdits,
  deriveParseRules,
  describeEdits,
  groupEditDescriptions,
  hasOversizedAdditionGuard,
  seedChipsFromJson,
  EDITABLE_AXES,
  type Chip,
  type EditableAxis,
} from '@/features/wireframes/interpretation-edit';
import { InterpretationAdditionGuard } from '@/features/wireframes/review-interpretation-addition-guard';
import {
  countParseRuleDrafts,
  draftLimitStatus,
  MAX_PARSE_RULE_DRAFTS,
} from '@/features/wireframes/parse-rule-draft-limit';
import { ruleIdempotencyKey } from '@/features/wireframes/parse-rule-idempotency';
import { PreviousParseCandidatesNotice } from '@/features/wireframes/previous-parse-candidates-notice';
import { correctionCandidatesQueryKey } from '@/features/wireframes/review-inquiry-api';
import {
  createParsePatchCandidate,
  discardParsePatchCandidate,
  parseRuleErrorMessage,
  resolutionAxisLabels,
  validateParseRuleBody,
} from '@/features/wireframes/review-parse-rule-api';
import { useCorrectionCandidates } from '@/features/wireframes/use-correction-candidates';
import styles from '@/features/wireframes/review-interpretation-editor.module.css';
import { ApiClientError } from '@/lib/api/error';

interface ParseInterpretationEditorProps {
  feedbackId: string;
  parsedQueryJson: string | null;
}

const MAX_CHIP_VALUE_LENGTH = 20;

export function ParseInterpretationEditor({
  feedbackId,
  parsedQueryJson,
}: ParseInterpretationEditorProps) {
  const queryClient = useQueryClient();
  const seeded = seedChipsFromJson(parsedQueryJson);
  // 원본 스냅샷은 다시 세팅하지 않는 값이라 state 로 보존한다 (렌더 중 ref.current 를 읽지 않기 위함).
  const [originalChips] = useState<Chip[]>(() => seeded ?? []);
  const [chips, setChips] = useState<Chip[]>(() => seeded ?? []);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState('');
  const [draggingId, setDraggingId] = useState<string | null>(null);
  const [dropTargetAxis, setDropTargetAxis] = useState<EditableAxis | null>(null);
  const newChipCounter = useRef(0);
  const guardOptions = additionGuardOptions(originalChips);
  const [selectedGuardId, setSelectedGuardId] = useState<string | null>(
    () => defaultAdditionGuard(guardOptions)?.id ?? null,
  );
  const [guardPicker, setGuardPicker] = useState<{ addAxis: EditableAxis | null } | null>(null);
  // 이 화면이 마지막으로 POST 를 보내기 시작한 규칙 묶음. 첫 POST 전에 기록한다 — 응답이 유실되거나 일부만
  // 저장돼도 서버에 남았을 수 있다고 본다 (S15P21A501-309). 새로고침 뒤에는 서버 목록(previousCount)으로 안다.
  const [lastAttempt, setLastAttempt] = useState<string | null>(null);
  const candidates = useCorrectionCandidates(feedbackId, true);
  const previousCount = candidates.data?.parsePatches.length ?? 0;
  const suppressBlur = useRef(false);

  const selectedGuard = guardOptions.find((option) => option.id === selectedGuardId) ?? null;
  const guard = selectedGuard ? { axis: selectedGuard.axis, value: selectedGuard.value } : null;
  const edits = deriveEdits(originalChips, chips);
  const descriptions = describeEdits(edits);
  const descriptionGroups = groupEditDescriptions(descriptions);
  const rules = deriveParseRules(edits, guard);
  const draftCount = countParseRuleDrafts(rules.length, chips);
  const draftLimit = draftLimitStatus(draftCount);
  const draftCountId = useId();
  const ruleSignature = JSON.stringify(rules);
  // 저장 시 이전 후보를 먼저 폐기해야 하는지 (S15P21A501-317). 편집만으로는 폐기하지 않는다 — 담기 전에 떠나도
  // 이전에 담은 교정이 남는다. 같은 내용 재시도는 폐기하지 않고 같은 멱등성 키로 재전송한다.
  const mustDiscardFirst = lastAttempt === null ? previousCount > 0 : ruleSignature !== lastAttempt;

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
          // 새로 입력한 값(add·edit, 값을 고친 move)만 검사한다 (입력창 maxLength 로 이미 20자 이하로
          // 제한된다). remove 는 값을 새로 넣지 않고, 값을 고치지 않은 move 는 원본 항목을 value_from 으로
          // 가리켜 옮기므로 원본 값이 20자를 넘어도 된다 (서버 상한은 원본 값 100자).
          if (edit.kind === 'remove') return false;
          if (edit.kind === 'move' && edit.value === edit.fromValue) return false;
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
      // 이전 후보를 먼저 폐기한다. 폐기가 실패하면 POST 하지 않고, 다시 누르면 폐기부터 반복한다.
      if (mustDiscardFirst) await discardParsePatchCandidate(feedbackId);
      // 순서대로 보낸다. 중간에 실패하면 다시 눌러 전부 재전송하면 된다 — 이미 저장된 규칙은 같은 결정적
      // 멱등성 키라 서버가 기존 후보를 돌려주어 중복이 생기지 않는다.
      setLastAttempt(ruleSignature);
      for (const rule of rules) {
        await createParsePatchCandidate(
          feedbackId,
          rule,
          await ruleIdempotencyKey(feedbackId, rule),
        );
      }
      return rules.length;
    },
    onSuccess: () => {
      // 담은 뒤에도 편집 상태를 그대로 둔다 — '바뀌는 점'이 남아 무엇을 담았는지 계속 보인다.
      // 재클릭해도 규칙별 결정적 idempotency key 로 서버가 같은 후보를 돌려주어 중복이 생기지 않는다.
      queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] });
      return queryClient.invalidateQueries({ queryKey: correctionCandidatesQueryKey(feedbackId) });
    },
  });

  // 폐기 뒤 대기 후보를 다시 읽을 때까지 진행 중으로 둔다 — 옛 건수로 다시 폐기하지 않게 한다.
  const discard = useMutation({
    mutationKey: ['parse-patch-discard', feedbackId],
    mutationFn: () => discardParsePatchCandidate(feedbackId),
    onSuccess: () => {
      setLastAttempt(null);
      return queryClient.invalidateQueries({ queryKey: correctionCandidatesQueryKey(feedbackId) });
    },
  });

  // 저장 POST 는 누른 시점의 규칙을 담는다. 진행 중에 칩을 바꾸면 성공 뒤 '담았어요'가 실제로 담지 않은
  // 내용을 가리키고, 이전 후보 폐기가 아직 끝나지 않은 POST 와 엇갈린다 — 그동안 편집을 잠근다.
  // 검증 재검색 중에도 잠가, 검증 대상 후보가 도중에 바뀌지 않게 한다 (S15P21A501-317).
  const verifyPending = useIsMutating({ mutationKey: ['verification-run', feedbackId] }) > 0;
  // 대기 후보를 아직 못 읽었으면(조회 중·실패) 서버에 남은 교정을 모르므로 편집도 잠근다.
  const locked = save.isPending || verifyPending || !candidates.isSuccess;

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
    // 값을 담은 적 없는 새 빈 칩을 지우는 건 교정 변경이 아니다. 실제 칩(값 있음/기존)만 담았어요 표시를 거둔다(서버 후보는 다음 담기 때 교체).
    const target = chips.find((chip) => chip.id === id);
    if (target && !(target.isNew && !target.value.trim())) save.reset();
    setChips((prev) => prev.filter((chip) => chip.id !== id));
    if (editingId === id) setEditingId(null);
  }

  function addChip(axis: EditableAxis) {
    if (locked) return;
    // 빈 칩을 추가하는 것만으로는 교정 내용이 바뀌지 않는다 — 실제 값을 확정(commitEdit)할 때만 담았어요 표시를 거둔다.
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

  function requestAddChip(axis: EditableAxis) {
    if (locked || draftLimit.isAtLimit || guardOptions.length === 0) return;
    if (selectedGuard) {
      addChip(axis);
      return;
    }
    setGuardPicker({ addAxis: axis });
  }

  function selectAdditionGuard(id: string) {
    const option = guardOptions.find((item) => item.id === id);
    if (!option) return;
    const addAxis = guardPicker?.addAxis ?? null;
    if (id !== selectedGuardId && edits.some((edit) => edit.kind === 'add')) save.reset();
    setSelectedGuardId(id);
    setGuardPicker(null);
    if (addAxis && !draftLimit.isAtLimit) addChip(addAxis);
  }

  function commitEdit() {
    const id = editingId;
    if (id === null) return;
    const value = editDraft.trim();
    const chip = chips.find((item) => item.id === id);
    // 값이 그대로면(포커스만 옮김·재확정) 교정 내용이 안 바뀐 것이므로 담았어요 표시를 그대로 둔다.
    // 새 빈 칩을 빈 값으로 확정하는 것도 실제로는 아무것도 담기지 않으므로 교정 변경이 아니다.
    const changed = chip ? chip.value !== value : false;
    if (changed) save.reset();
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
    // 같은 축에 다시 떨구거나 대상이 없으면 실제 변경이 없다 — 표시를 그대로 둔다.
    const target = chips.find((chip) => chip.id === id);
    if (!target || target.axis === axis) return;
    save.reset();
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
        <PreviousParseCandidatesNotice
          count={previousCount}
          hasUnsavedEdits={!save.isPending && mustDiscardFirst && descriptions.length > 0}
          isBusy={locked || discard.isPending}
          isError={candidates.isError}
          onDiscard={() => discard.mutate()}
          onRetry={() => void candidates.refetch()}
        />

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
                  disabled={locked || guardOptions.length === 0 || draftLimit.isAtLimit}
                  onClick={() => requestAddChip(axis)}
                  type="button"
                >
                  +
                </button>
              </div>
            </div>
          ))}
        </div>

        <InterpretationAdditionGuard
          hasOversized={hasOversizedAdditionGuard(originalChips)}
          isBusy={locked}
          isChoosing={guardPicker !== null}
          onCancel={() => setGuardPicker(null)}
          onChange={() => setGuardPicker({ addAxis: null })}
          onSelect={selectAdditionGuard}
          options={guardOptions}
          selected={selectedGuard}
        />
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

        <div className={styles.saveRow}>
          <button
            aria-describedby={draftCountId}
            className={styles.primaryButton}
            disabled={locked || discard.isPending || descriptions.length === 0}
            onClick={() => save.mutate()}
            type="button"
          >
            {discard.isPending ? '이전 교정 폐기 중…' : save.isPending ? '담는 중…' : '교정 담기'}
          </button>
          <span
            className={styles.draftCount}
            data-at-limit={draftLimit.isAtLimit || undefined}
            id={draftCountId}
          >
            <span className="sr-only">담을 교정 수 </span>
            {draftLimit.label}
          </span>
        </div>
        <p aria-live="polite" className={styles.draftLimitNotice} role="status">
          {draftLimit.message}
        </p>

        {discard.isError ? (
          <div className={styles.errorGroup}>
            <p className={styles.hint} role="alert">
              이전 교정을 폐기하지 못했어요. 잠시 후 다시 시도해 주세요.
            </p>
            <button
              className={styles.secondaryButton}
              disabled={discard.isPending || verifyPending}
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
