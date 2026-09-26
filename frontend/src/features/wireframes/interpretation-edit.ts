import {
  type ParseRuleCandidateBody,
  type PatchOperation,
  PARSE_RULE_SYNTAX_VERSION,
  RESOLUTION_SCHEMA_VERSION,
  resolutionAxisLabels,
} from '@/features/wireframes/review-parse-rule-api';
import { parseResolution, type Resolution } from '@/features/wireframes/reviewer-resolution-state';

export type EditableAxis = 'incident_names' | 'entities' | 'locations' | 'expanded_terms';

export const EDITABLE_AXES: EditableAxis[] = [
  'incident_names',
  'entities',
  'locations',
  'expanded_terms',
];

export const TYPED_EDITABLE: ReadonlySet<EditableAxis> = new Set(['entities', 'locations']);

export interface Chip {
  id: string;
  axis: EditableAxis;
  value: string;
  type?: string;
  origin?: string;
  isNew?: boolean;
}

export interface AdditionGuardOption {
  id: string;
  axis: EditableAxis;
  value: string;
  isExplicit: boolean;
}

const MAX_ADDITION_GUARD_LENGTH = 100;

/** 새 항목이 다른 검색에 무조건 붙지 않도록, 원본 해석에서 적용 조건으로 고를 수 있는 값. */
export function additionGuardOptions(original: Chip[]): AdditionGuardOption[] {
  const indexByCondition = new Map<string, number>();
  const options: AdditionGuardOption[] = [];
  for (const chip of original) {
    const value = chip.value;
    if (chip.isNew || !value.trim() || value.length > MAX_ADDITION_GUARD_LENGTH) continue;
    const key = `${chip.axis}\u0000${value}`;
    const option = {
      id: chip.id,
      axis: chip.axis,
      value,
      isExplicit: chip.origin?.startsWith('explicit') ?? false,
    };
    const duplicateIndex = indexByCondition.get(key);
    if (duplicateIndex === undefined) {
      indexByCondition.set(key, options.length);
      options.push(option);
    } else if (option.isExplicit && !options[duplicateIndex].isExplicit) {
      options[duplicateIndex] = option;
    }
  }
  return options;
}

export function hasOversizedAdditionGuard(original: Chip[]): boolean {
  return original.some(
    (chip) =>
      !chip.isNew && Boolean(chip.value.trim()) && chip.value.length > MAX_ADDITION_GUARD_LENGTH,
  );
}

/** 자동 선택은 재해석 뒤에도 비교적 안정적인 명시값에만 한다. 추론값은 검수자가 직접 골라야 한다. */
export function defaultAdditionGuard(options: AdditionGuardOption[]): AdditionGuardOption | null {
  for (const axis of ['incident_names', 'entities', 'locations'] as const) {
    const option = options.find((item) => item.axis === axis && item.isExplicit);
    if (option) return option;
  }
  return null;
}

export function seedChips(resolution: Resolution): Chip[] {
  const chips: Chip[] = [];
  for (const axis of EDITABLE_AXES) {
    if (axis === 'expanded_terms') {
      resolution.expanded_terms
        .filter((value): value is string => typeof value === 'string' && value.length > 0)
        .forEach((value, index) => chips.push({ id: `${axis}#${index}`, axis, value }));
    } else {
      resolution[axis]
        .filter((item) => typeof item.value === 'string' && item.value.length > 0)
        .forEach((item, index) =>
          chips.push({
            id: `${axis}#${index}`,
            axis,
            value: item.value,
            type: TYPED_EDITABLE.has(axis) ? (item.type ?? defaultType(axis)) : item.type,
            origin: item.origin,
          }),
        );
    }
  }
  return chips;
}

/** 문의 당시 해석 스냅샷을 칩으로 바꾼다. 없거나 깨진 스냅샷은 편집할 수 없다. */
export function seedChipsFromJson(json: string | null): Chip[] | null {
  if (!json) return null;
  try {
    return seedChips(parseResolution(json));
  } catch {
    return null;
  }
}

export type ChipEdit =
  | { kind: 'remove'; axis: EditableAxis; value: string; type?: string }
  | { kind: 'edit'; axis: EditableAxis; from: string; to: string; type?: string }
  | {
      kind: 'move';
      from: EditableAxis;
      to: EditableAxis;
      value: string;
      fromValue: string;
      type?: string;
      fromType?: string;
    }
  | { kind: 'add'; axis: EditableAxis; value: string; type?: string };

export function deriveEdits(original: Chip[], current: Chip[]): ChipEdit[] {
  const originalById = new Map(original.map((chip) => [chip.id, chip]));
  const seen = new Set<string>();
  const edits: ChipEdit[] = [];
  for (const chip of current) {
    const value = chip.value.trim();
    if (chip.isNew) {
      if (value) edits.push({ kind: 'add', axis: chip.axis, value, type: chip.type });
      continue;
    }
    const before = originalById.get(chip.id);
    if (!before) continue;
    seen.add(chip.id);
    if (!value) {
      edits.push({ kind: 'remove', axis: before.axis, value: before.value, type: before.type });
      continue;
    }
    if (chip.axis !== before.axis) {
      edits.push({
        kind: 'move',
        from: before.axis,
        to: chip.axis,
        value,
        fromValue: before.value,
        type: chip.type,
        fromType: before.type,
      });
    } else if (value !== before.value) {
      edits.push({
        kind: 'edit',
        axis: before.axis,
        from: before.value,
        to: value,
        type: before.type,
      });
    }
  }
  for (const chip of original) {
    if (!seen.has(chip.id)) {
      edits.push({ kind: 'remove', axis: chip.axis, value: chip.value, type: chip.type });
    }
  }
  return edits;
}

export function defaultType(axis: EditableAxis): string | undefined {
  if (axis === 'locations') return 'location';
  if (axis === 'entities') return 'organization';
  return undefined;
}

function typeIf(axis: EditableAxis, type: string | undefined): { type?: string } {
  return TYPED_EDITABLE.has(axis) && type ? { type } : {};
}

function body(
  all: ParseRuleCandidateBody['condition']['all'],
  operations: PatchOperation[],
): ParseRuleCandidateBody {
  return {
    condition: {
      syntax_version: PARSE_RULE_SYNTAX_VERSION,
      resolution_schema_version: RESOLUTION_SCHEMA_VERSION,
      all,
    },
    patch: { syntax_version: PARSE_RULE_SYNTAX_VERSION, operations },
  };
}

export function deriveParseRules(
  edits: ChipEdit[],
  guard: { axis: EditableAxis; value: string } | null,
): ParseRuleCandidateBody[] {
  const rules: ParseRuleCandidateBody[] = [];
  for (const edit of edits) {
    if (edit.kind === 'remove') {
      rules.push(
        body(
          [{ axis: edit.axis, op: 'has_value', value: edit.value }],
          [
            {
              op: 'remove_item',
              axis: edit.axis,
              value: edit.value,
              ...typeIf(edit.axis, edit.type),
            },
          ],
        ),
      );
    } else if (edit.kind === 'edit') {
      rules.push(
        body(
          [{ axis: edit.axis, op: 'has_value', value: edit.from }],
          [
            {
              op: 'remove_item',
              axis: edit.axis,
              value: edit.from,
              ...typeIf(edit.axis, edit.type),
            },
            { op: 'add_item', axis: edit.axis, value: edit.to, ...typeIf(edit.axis, edit.type) },
          ],
        ),
      );
    } else if (edit.kind === 'move') {
      // 값을 고치지 않은 이동은 원본 항목을 가리켜(value_from) 옮긴다. 원본 AI 값은 길이 제한이 없어 리터럴로
      // 다시 적으면 새 값 상한(20자)에 걸리고, 참조로 옮기면 원본 출처·원문 구간도 승계된다 (FRD F-11).
      // 이동하면서 값을 고쳤다면 새로 입력한 값이라 리터럴로 적는다 (입력창 maxLength 로 20자 이하).
      const moved: Pick<PatchOperation, 'value' | 'value_from'> =
        edit.value === edit.fromValue
          ? {
              value_from: {
                axis: edit.from,
                value: edit.fromValue,
                ...typeIf(edit.from, edit.fromType),
              },
            }
          : { value: edit.value };
      rules.push(
        body(
          [{ axis: edit.from, op: 'has_value', value: edit.fromValue }],
          [
            {
              op: 'remove_item',
              axis: edit.from,
              value: edit.fromValue,
              ...typeIf(edit.from, edit.fromType),
            },
            {
              op: 'add_item',
              axis: edit.to,
              ...moved,
              ...typeIf(edit.to, defaultType(edit.to)),
            },
          ],
        ),
      );
    } else if (edit.kind === 'add' && guard) {
      rules.push(
        body(
          [{ axis: guard.axis, op: 'has_value', value: guard.value }],
          [
            {
              op: 'add_item',
              axis: edit.axis,
              value: edit.value,
              ...typeIf(edit.axis, edit.type ?? defaultType(edit.axis)),
            },
          ],
        ),
      );
    }
  }
  return rules;
}

export function describeEdits(edits: ChipEdit[]): { key: string; text: string }[] {
  return edits.map((edit) => {
    if (edit.kind === 'remove')
      return { key: '삭제', text: `${resolutionAxisLabels[edit.axis]}에서 ‘${edit.value}’ 제거` };
    if (edit.kind === 'edit')
      return {
        key: '수정',
        text: `${resolutionAxisLabels[edit.axis]} ‘${edit.from}’를 ‘${edit.to}’로`,
      };
    if (edit.kind === 'move')
      return {
        key: '수정',
        text: `‘${edit.fromValue}’를 ${resolutionAxisLabels[edit.from]}에서 ${resolutionAxisLabels[edit.to]}으로${
          edit.value !== edit.fromValue ? ` (‘${edit.value}’로 수정)` : ''
        }`,
      };
    return { key: '추가', text: `${resolutionAxisLabels[edit.axis]}에 ‘${edit.value}’` };
  });
}

export function groupEditDescriptions(descriptions: { key: string; text: string }[]) {
  return [
    { key: '추가', tone: 'add' },
    { key: '삭제', tone: 'remove' },
    { key: '수정', tone: 'edit' },
  ].map((group) => ({
    ...group,
    items: descriptions.filter((description) => description.key === group.key),
  }));
}
