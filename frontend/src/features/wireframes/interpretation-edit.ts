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
