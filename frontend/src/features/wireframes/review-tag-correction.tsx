'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useId, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  MAX_TAG_OPERATIONS,
  createTagCorrectionCandidate,
  tagCorrectionErrorMessage,
  type TagCorrectionAction,
  type TagCorrectionOperation,
  type TagCorrectionScope,
} from '@/features/wireframes/review-tag-correction-api';
import styles from '@/features/wireframes/reviewer.module.css';

const actionLabels: Record<TagCorrectionAction, string> = {
  APPROVE: '승인(추가·복원)',
  REJECT: '반려',
  WITHDRAW: '개입 해제',
};

const scopeLabels: Record<TagCorrectionScope, string> = {
  SCENE: '이 장면만',
  CLIP: '클립 전체',
};

// 태그 유형 11종(F-04). 서버 어휘 그대로 보낸다.
const tagTypeLabels: Record<string, string> = {
  person: '인물',
  organization: '기관',
  location: '장소',
  facility: '시설',
  keyword: '검색 의미어',
  event: '사건',
  season: '계절',
  weather: '날씨',
  scene_type: '장면 유형',
  filmed_date: '촬영일',
  broadcast_date: '방송일',
};

interface DraftRow extends TagCorrectionOperation {
  key: number;
}

function emptyRow(key: number): DraftRow {
  return {
    key,
    action: 'APPROVE',
    scope: 'SCENE',
    tagType: 'location',
    matchValue: '',
    displayName: '',
  };
}

interface TagCorrectionFormProps {
  feedbackId: string;
}

export function TagCorrectionForm({ feedbackId }: TagCorrectionFormProps) {
  const queryClient = useQueryClient();
  const fieldId = useId();
  const [rows, setRows] = useState<DraftRow[]>([emptyRow(0)]);
  const [nextKey, setNextKey] = useState(1);
  const [validationError, setValidationError] = useState('');
  const mutation = useMutation({
    mutationFn: (operations: TagCorrectionOperation[]) =>
      createTagCorrectionCandidate(feedbackId, operations),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['review-inquiries'] }),
        queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
      ]);
    },
  });
  const contractMessage = mutation.isError ? tagCorrectionErrorMessage(mutation.error) : null;

  function updateRow(key: number, patch: Partial<DraftRow>) {
    setRows((current) => current.map((row) => (row.key === key ? { ...row, ...patch } : row)));
    setValidationError('');
    mutation.reset();
  }

  function addRow() {
    setRows((current) => [...current, emptyRow(nextKey)]);
    setNextKey(nextKey + 1);
    setValidationError('');
    mutation.reset();
  }

  function removeRow(key: number) {
    setRows((current) => current.filter((row) => row.key !== key));
    setValidationError('');
    mutation.reset();
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mutation.isPending) return;
    const operations = rows.map((row) => ({
      action: row.action,
      scope: row.scope,
      tagType: row.tagType,
      matchValue: row.matchValue.trim(),
      // 표시 이름을 비워두면 태그 값과 같게 저장한다. 서버는 빈 값을 받지 않는다.
      displayName: row.displayName.trim() || row.matchValue.trim(),
    }));
    if (operations.some((operation) => !operation.matchValue)) {
      setValidationError('모든 변경안에 태그 값을 입력해 주세요.');
      return;
    }
    setValidationError('');
    mutation.mutate(operations);
  }

  return (
    <section className="rounded-2xl border border-(--line) p-5">
      <h2 className="font-bold">태그 변경안</h2>
      <p className="mt-2 text-sm text-(--muted)">
        변경안은 후보로만 저장됩니다. 검증과 확정 전까지 검색에 반영되지 않습니다. 태그를 바꾸려면
        기존 값을 반려하는 행과 올바른 값을 승인하는 행을 함께 추가해 한 번에 저장하세요.
      </p>
      <form className="mt-4 grid gap-4" onSubmit={handleSubmit}>
        {rows.map((row, index) => (
          <fieldset className="grid gap-3 rounded-xl border border-(--line) p-4" key={row.key}>
            <legend className="px-1 text-sm font-bold">변경안 {index + 1}</legend>
            <div className="grid gap-3 md:grid-cols-2">
              <label
                className="grid gap-2 text-sm font-bold"
                htmlFor={`${fieldId}-${row.key}-action`}
              >
                작업
                <select
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  id={`${fieldId}-${row.key}-action`}
                  onChange={(event) =>
                    updateRow(row.key, { action: event.target.value as TagCorrectionAction })
                  }
                  value={row.action}
                >
                  {Object.entries(actionLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label
                className="grid gap-2 text-sm font-bold"
                htmlFor={`${fieldId}-${row.key}-scope`}
              >
                적용 범위
                <select
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  id={`${fieldId}-${row.key}-scope`}
                  onChange={(event) =>
                    updateRow(row.key, { scope: event.target.value as TagCorrectionScope })
                  }
                  value={row.scope}
                >
                  {Object.entries(scopeLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label
                className="grid gap-2 text-sm font-bold"
                htmlFor={`${fieldId}-${row.key}-tag-type`}
              >
                태그 유형
                <select
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  id={`${fieldId}-${row.key}-tag-type`}
                  onChange={(event) => updateRow(row.key, { tagType: event.target.value })}
                  value={row.tagType}
                >
                  {Object.entries(tagTypeLabels).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label
                className="grid gap-2 text-sm font-bold"
                htmlFor={`${fieldId}-${row.key}-match-value`}
              >
                태그 값 (필수)
                <input
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  id={`${fieldId}-${row.key}-match-value`}
                  maxLength={255}
                  onChange={(event) => updateRow(row.key, { matchValue: event.target.value })}
                  value={row.matchValue}
                />
              </label>
              <label
                className="grid gap-2 text-sm font-bold md:col-span-2"
                htmlFor={`${fieldId}-${row.key}-display-name`}
              >
                표시 이름 (비우면 태그 값과 같게 저장)
                <input
                  className="rounded-xl border border-(--line) bg-(--surface) p-3 font-normal"
                  disabled={mutation.isPending}
                  id={`${fieldId}-${row.key}-display-name`}
                  maxLength={255}
                  onChange={(event) => updateRow(row.key, { displayName: event.target.value })}
                  value={row.displayName}
                />
              </label>
            </div>
            <div>
              <button
                className={styles.secondaryButton}
                disabled={mutation.isPending || rows.length === 1}
                onClick={() => removeRow(row.key)}
                type="button"
              >
                변경안 {index + 1} 삭제
              </button>
            </div>
          </fieldset>
        ))}
        <div className="grid gap-2">
          <button
            className={styles.secondaryButton}
            disabled={mutation.isPending || rows.length >= MAX_TAG_OPERATIONS}
            onClick={addRow}
            type="button"
          >
            변경안 추가
          </button>
          {rows.length >= MAX_TAG_OPERATIONS ? (
            <p className="text-sm text-(--muted)">
              한 번에 저장할 수 있는 변경안은 {MAX_TAG_OPERATIONS}개까지입니다. 지금까지 만든
              변경안을 저장한 뒤 이어서 추가해 주세요.
            </p>
          ) : null}
        </div>
        {validationError ? (
          <p className="text-sm text-(--danger)" role="alert">
            {validationError}
          </p>
        ) : null}
        <p aria-live="polite" className="sr-only" role="status">
          {mutation.isPending ? '태그 변경안을 저장하고 있습니다.' : ''}
        </p>
        {mutation.isError ? (
          <div className="grid gap-3">
            <ApiErrorNotice error={mutation.error} />
            {contractMessage ? <p className="text-sm">{contractMessage}</p> : null}
          </div>
        ) : null}
        {mutation.isSuccess ? (
          <p className="text-sm text-(--positive)" role="status">
            변경안 {mutation.data.created}건을 후보로 저장했습니다. 검증과 확정 전까지 검색에
            반영되지 않습니다.
          </p>
        ) : null}
        <button className={styles.primaryButton} disabled={mutation.isPending} type="submit">
          {mutation.isPending ? '저장 중…' : '변경안 저장'}
        </button>
      </form>
    </section>
  );
}
