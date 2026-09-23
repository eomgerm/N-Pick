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
      resolution.expanded_terms.forEach((value, index) =>
        chips.push({ id: `${axis}#${index}`, axis, value }),
      );
    } else {
      resolution[axis].forEach((item, index) =>
        chips.push({ id: `${axis}#${index}`, axis, value: item.value, type: item.type, origin: item.origin }),
      );
    }
  }
  return chips;
}

export type ChipEdit =
  | { kind: 'remove'; axis: EditableAxis; value: string; type?: string }
  | { kind: 'edit'; axis: EditableAxis; from: string; to: string; type?: string }
  | { kind: 'move'; from: EditableAxis; to: EditableAxis; value: string; type?: string }
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
      edits.push({ kind: 'move', from: before.axis, to: chip.axis, value, type: chip.type });
    } else if (value !== before.value) {
      edits.push({ kind: 'edit', axis: before.axis, from: before.value, to: value, type: before.type });
    }
  }
  for (const chip of original) {
    if (!seen.has(chip.id)) {
      edits.push({ kind: 'remove', axis: chip.axis, value: chip.value, type: chip.type });
    }
  }
  return edits;
}
