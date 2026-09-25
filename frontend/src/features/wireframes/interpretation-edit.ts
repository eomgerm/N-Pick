import {
  type ParseRuleCandidateBody,
  type PatchOperation,
  PARSE_RULE_SYNTAX_VERSION,
  RESOLUTION_SCHEMA_VERSION,
  resolutionAxisLabels,
} from '@/features/wireframes/review-parse-rule-api';
import { type Resolution } from '@/features/wireframes/reviewer-resolution-state';

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
              value: edit.value,
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

/**
 * 여러 규칙을 후보 1건으로 합친다. 저장 1회가 후보 1건이 되어야, 다시 편집해 저장할 때 이전 후보를
 * 통째로 폐기·교체할 수 있고 멱등성 키도 저장 단위로 하나만 유지된다. 조건(`all`)은 동일 술어를
 * 합치고(중복 제거), 변경 연산(`operations`)은 순서를 지켜 이어 붙인다 — 백엔드가 순차 적용한다.
 */
export function combineParseRules(rules: ParseRuleCandidateBody[]): ParseRuleCandidateBody {
  const all: ParseRuleCandidateBody['condition']['all'] = [];
  const seen = new Set<string>();
  const operations: PatchOperation[] = [];
  for (const rule of rules) {
    for (const predicate of rule.condition.all) {
      const key = JSON.stringify(predicate);
      if (seen.has(key)) continue;
      seen.add(key);
      all.push(predicate);
    }
    operations.push(...rule.patch.operations);
  }
  return body(all, operations);
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
