'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import { type FormEvent, useId, useRef, useState } from 'react';

import { ApiErrorNotice } from '@/components/api-error-notice';
import {
  conditionOpLabels,
  createParsePatchCandidate,
  parseRuleErrorMessage,
  patchOpLabels,
  PARSE_RULE_SYNTAX_VERSION,
  RESOLUTION_SCHEMA_VERSION,
  resolutionAxisLabels,
  validateParseRuleBody,
  type ConditionOp,
  type ConditionPredicate,
  type ParseRuleCandidateBody,
  type PatchOp,
  type PatchOperation,
  type ResolutionAxis,
} from '@/features/wireframes/review-parse-rule-api';
import { SuccessToast } from '@/features/wireframes/success-toast';
import styles from '@/features/wireframes/reviewer.module.css';
import { createIdempotencyKey } from '@/lib/api/idempotency';

interface ParsePatchCandidateFormProps {
  feedbackId: string;
}

const fieldClass = 'rounded-xl border border-(--line) bg-(--surface) p-2.5 font-normal';
const labelClass = 'grid gap-1.5 text-xs font-bold';

const emptyPredicate: ConditionPredicate = { axis: 'locations', op: 'has_value', value: '' };
const emptyOperation: PatchOperation = { op: 'remove_item', axis: 'locations', value: '' };

function axisOptions() {
  return Object.entries(resolutionAxisLabels).map(([value, label]) => (
    <option key={value} value={value}>
      {label}
    </option>
  ));
}

interface PredicateRowProps {
  predicate: ConditionPredicate;
  index: number;
  disabled: boolean;
  onChange: (next: ConditionPredicate) => void;
  onRemove: () => void;
}

function PredicateRow({ predicate, index, disabled, onChange, onRemove }: PredicateRowProps) {
  const id = useId();
  return (
    <fieldset className="rounded-xl border border-(--line) p-4" disabled={disabled}>
      <legend className="px-1 text-xs font-bold text-(--muted)">조건 {index + 1}</legend>
      <div className="grid gap-3 md:grid-cols-4">
        <label className={labelClass} htmlFor={`${id}-axis`}>
          해석 항목
          <select
            className={fieldClass}
            id={`${id}-axis`}
            onChange={(event) =>
              onChange({ ...predicate, axis: event.target.value as ResolutionAxis })
            }
            value={predicate.axis}
          >
            {axisOptions()}
          </select>
        </label>
        <label className={labelClass} htmlFor={`${id}-op`}>
          조건
          <select
            className={fieldClass}
            id={`${id}-op`}
            onChange={(event) => onChange({ ...predicate, op: event.target.value as ConditionOp })}
            value={predicate.op}
          >
            {Object.entries(conditionOpLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className={labelClass} htmlFor={`${id}-type`}>
          유형 (해당 항목만)
          <input
            className={fieldClass}
            id={`${id}-type`}
            onChange={(event) => onChange({ ...predicate, type: event.target.value })}
            value={predicate.type ?? ''}
          />
        </label>
        <label className={labelClass} htmlFor={`${id}-value`}>
          값 (해당 조건만)
          <input
            className={fieldClass}
            id={`${id}-value`}
            onChange={(event) => onChange({ ...predicate, value: event.target.value })}
            value={predicate.value ?? ''}
          />
        </label>
      </div>
      <button
        className="mt-3 inline-flex items-center gap-1 text-sm underline"
        onClick={onRemove}
        type="button"
      >
        <Trash2 aria-hidden="true" className="size-4" /> 조건 {index + 1} 삭제
      </button>
    </fieldset>
  );
}

interface OperationRowProps {
  operation: PatchOperation;
  index: number;
  disabled: boolean;
  onChange: (next: PatchOperation) => void;
  onRemove: () => void;
}

function OperationRow({ operation, index, disabled, onChange, onRemove }: OperationRowProps) {
  const id = useId();
  const reusesValue = operation.value_from !== undefined;
  return (
    <fieldset className="rounded-xl border border-(--line) p-4" disabled={disabled}>
      <legend className="px-1 text-xs font-bold text-(--muted)">변경 {index + 1}</legend>
      <div className="grid gap-3 md:grid-cols-4">
        <label className={labelClass} htmlFor={`${id}-op`}>
          변경 방식
          <select
            className={fieldClass}
            id={`${id}-op`}
            onChange={(event) => onChange({ ...operation, op: event.target.value as PatchOp })}
            value={operation.op}
          >
            {Object.entries(patchOpLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className={labelClass} htmlFor={`${id}-axis`}>
          해석 항목
          <select
            className={fieldClass}
            id={`${id}-axis`}
            onChange={(event) =>
              onChange({ ...operation, axis: event.target.value as ResolutionAxis })
            }
            value={operation.axis}
          >
            {axisOptions()}
          </select>
        </label>
        <label className={labelClass} htmlFor={`${id}-type`}>
          유형 (해당 항목만)
          <input
            className={fieldClass}
            id={`${id}-type`}
            onChange={(event) => onChange({ ...operation, type: event.target.value })}
            value={operation.type ?? ''}
          />
        </label>
        <label className={labelClass} htmlFor={`${id}-value`}>
          값
          <input
            className={fieldClass}
            disabled={reusesValue}
            id={`${id}-value`}
            onChange={(event) => onChange({ ...operation, value: event.target.value })}
            value={operation.value ?? ''}
          />
        </label>
        <label className={labelClass} htmlFor={`${id}-start`}>
          시작일 (날짜 조건만)
          <input
            className={fieldClass}
            id={`${id}-start`}
            onChange={(event) => onChange({ ...operation, start: event.target.value })}
            type="date"
            value={operation.start ?? ''}
          />
        </label>
        <label className={labelClass} htmlFor={`${id}-end`}>
          종료일 (제외, 날짜 조건만)
          <input
            className={fieldClass}
            id={`${id}-end`}
            onChange={(event) => onChange({ ...operation, end_exclusive: event.target.value })}
            type="date"
            value={operation.end_exclusive ?? ''}
          />
        </label>
      </div>
      <label className="mt-3 flex items-center gap-2 text-xs font-bold">
        <input
          checked={reusesValue}
          onChange={(event) =>
            onChange({
              ...operation,
              value: event.target.checked ? '' : operation.value,
              value_from: event.target.checked ? { axis: 'locations', value: '' } : undefined,
            })
          }
          type="checkbox"
        />
        원본 해석의 다른 항목 값을 가져와 추가
      </label>
      {operation.value_from ? (
        <div className="mt-3 grid gap-3 rounded-xl bg-(--surface-muted) p-3 md:grid-cols-3">
          <label className={labelClass} htmlFor={`${id}-from-axis`}>
            가져올 항목
            <select
              className={fieldClass}
              id={`${id}-from-axis`}
              onChange={(event) =>
                onChange({
                  ...operation,
                  value_from: {
                    ...operation.value_from!,
                    axis: event.target.value as ResolutionAxis,
                  },
                })
              }
              value={operation.value_from.axis}
            >
              {axisOptions()}
            </select>
          </label>
          <label className={labelClass} htmlFor={`${id}-from-type`}>
            가져올 유형
            <input
              className={fieldClass}
              id={`${id}-from-type`}
              onChange={(event) =>
                onChange({
                  ...operation,
                  value_from: { ...operation.value_from!, type: event.target.value },
                })
              }
              value={operation.value_from.type ?? ''}
            />
          </label>
          <label className={labelClass} htmlFor={`${id}-from-value`}>
            가져올 값
            <input
              className={fieldClass}
              id={`${id}-from-value`}
              onChange={(event) =>
                onChange({
                  ...operation,
                  value_from: { ...operation.value_from!, value: event.target.value },
                })
              }
              value={operation.value_from.value ?? ''}
            />
          </label>
        </div>
      ) : null}
      <button
        className="mt-3 inline-flex items-center gap-1 text-sm underline"
        onClick={onRemove}
        type="button"
      >
        <Trash2 aria-hidden="true" className="size-4" /> 변경 {index + 1} 삭제
      </button>
    </fieldset>
  );
}

export function ParsePatchCandidateForm({ feedbackId }: ParsePatchCandidateFormProps) {
  const queryClient = useQueryClient();
  const [predicates, setPredicates] = useState<ConditionPredicate[]>([emptyPredicate]);
  const [operations, setOperations] = useState<PatchOperation[]>([emptyOperation]);
  const [replacesRuleId, setReplacesRuleId] = useState('');
  const [validationError, setValidationError] = useState('');
  // 같은 초안의 재전송은 후보를 중복 생성하지 않는다. 초안이 바뀌면 새 요청이므로 키도 새로 만든다.
  const draftKey = useRef<{ draft: string; key: string } | null>(null);
  const replacesId = useId();

  function buildBody(): ParseRuleCandidateBody {
    return {
      condition: {
        syntax_version: PARSE_RULE_SYNTAX_VERSION,
        resolution_schema_version: RESOLUTION_SCHEMA_VERSION,
        all: predicates,
      },
      patch: { syntax_version: PARSE_RULE_SYNTAX_VERSION, operations },
      ...(replacesRuleId.trim() ? { replacesRuleId: replacesRuleId.trim() } : {}),
    };
  }

  const mutation = useMutation({
    mutationFn: () => {
      const body = buildBody();
      const draft = JSON.stringify(body);
      if (draftKey.current?.draft !== draft)
        draftKey.current = { draft, key: createIdempotencyKey() };
      return createParsePatchCandidate(feedbackId, body, draftKey.current.key);
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['review-inquiry', feedbackId] }),
  });

  function edit() {
    setValidationError('');
    mutation.reset();
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const problem = validateParseRuleBody(buildBody());
    if (problem) {
      setValidationError(problem);
      return;
    }
    setValidationError('');
    mutation.mutate();
  }

  const serverMessage = mutation.isError ? parseRuleErrorMessage(mutation.error) : null;
  return (
    <section className="rounded-2xl border border-(--line) p-5">
      <h2 className="font-bold">해석 교정 후보</h2>
      <p className="mt-2 text-sm text-(--muted)">
        AI 원본 해석이 아래 조건과 맞을 때만 변경을 적용하는 규칙을 만듭니다. 저장한 후보는 검증과
        확정을 거치기 전까지 검색에 반영되지 않습니다.
      </p>

      <form className="mt-4 grid gap-5" onSubmit={handleSubmit}>
        <div className="grid gap-3" role="group" aria-label="적용 조건">
          <h3 className="text-sm font-bold">적용 조건 (모두 만족할 때 적용)</h3>
          {predicates.map((predicate, index) => (
            <PredicateRow
              disabled={mutation.isPending}
              index={index}
              key={index}
              onChange={(next) => {
                setPredicates(predicates.map((item, at) => (at === index ? next : item)));
                edit();
              }}
              onRemove={() => {
                setPredicates(predicates.filter((_, at) => at !== index));
                edit();
              }}
              predicate={predicate}
            />
          ))}
          <button
            className={styles.secondaryButton}
            disabled={mutation.isPending}
            onClick={() => {
              setPredicates([...predicates, emptyPredicate]);
              edit();
            }}
            type="button"
          >
            <Plus aria-hidden="true" className="size-4" /> 조건 추가
          </button>
        </div>

        <div className="grid gap-3" role="group" aria-label="변경 내용">
          <h3 className="text-sm font-bold">변경 내용</h3>
          {operations.map((operation, index) => (
            <OperationRow
              disabled={mutation.isPending}
              index={index}
              key={index}
              onChange={(next) => {
                setOperations(operations.map((item, at) => (at === index ? next : item)));
                edit();
              }}
              onRemove={() => {
                setOperations(operations.filter((_, at) => at !== index));
                edit();
              }}
              operation={operation}
            />
          ))}
          <button
            className={styles.secondaryButton}
            disabled={mutation.isPending}
            onClick={() => {
              setOperations([...operations, emptyOperation]);
              edit();
            }}
            type="button"
          >
            <Plus aria-hidden="true" className="size-4" /> 변경 추가
          </button>
        </div>

        <label className="grid gap-2 text-sm font-bold md:max-w-xs" htmlFor={replacesId}>
          교체 대상 규칙 번호 (선택)
          <input
            className={fieldClass}
            disabled={mutation.isPending}
            id={replacesId}
            inputMode="numeric"
            onChange={(event) => {
              setReplacesRuleId(event.target.value);
              edit();
            }}
            pattern="[1-9][0-9]*"
            value={replacesRuleId}
          />
        </label>

        {validationError ? (
          <p className="text-sm text-(--danger)" role="alert">
            {validationError}
          </p>
        ) : null}
        {mutation.isError ? (
          <div className="grid gap-2">
            <ApiErrorNotice error={mutation.error} />
            {serverMessage ? <p className="text-sm">{serverMessage}</p> : null}
          </div>
        ) : null}
        {mutation.isPending ? (
          <p aria-live="polite" className="text-sm" role="status">
            해석 교정 후보를 저장하고 있습니다.
          </p>
        ) : null}
        <SuccessToast
          message={
            mutation.isSuccess
              ? `해석 교정 후보를 저장했습니다. 규칙 번호 ${mutation.data.searchRuleId}, 검증 전까지 검색에 반영되지 않습니다.`
              : ''
          }
        />
        <button className={styles.primaryButton} disabled={mutation.isPending} type="submit">
          {mutation.isPending ? '저장 중…' : '해석 교정 후보 저장'}
        </button>
      </form>
    </section>
  );
}
