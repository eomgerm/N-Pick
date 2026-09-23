export interface ResolutionTerm {
  value: string;
  type?: string;
  origin: string;
  query_span: { start: number; end: number } | null;
  confidence: number;
}

export interface Resolution {
  schema_version: string;
  intent: string;
  date_windows: { field: string; start: string; end_exclusive: string; origin: string }[];
  incident_names: ResolutionTerm[];
  entities: ResolutionTerm[];
  locations: ResolutionTerm[];
  expanded_terms: string[];
  confidence: number;
}

export type ResolutionTermField = 'incident_names' | 'entities' | 'locations' | 'expanded_terms';

// The editor owns only readable terms; the full snapshot and date/filter metadata survive each edit.
export function parseResolution(value: string): Resolution {
  return JSON.parse(value) as Resolution;
}

export function updateResolutionTerms(
  value: string,
  field: ResolutionTermField,
  input: string,
  entityType?: 'person' | 'organization',
): string {
  const resolution = parseResolution(value);
  const terms = input === '' ? [] : input.split(',');
  if (field === 'expanded_terms') {
    resolution[field] = terms;
  } else {
    const previous = resolution[field].filter((item) => !entityType || item.type === entityType);
    const updated = terms.map((term, index) => {
      const unchanged = previous.find((item) => item.value.trim() === term.trim());
      if (unchanged) return { ...unchanged, value: term };
      return {
        ...(field === 'entities'
          ? { type: entityType ?? previous[index]?.type ?? 'organization' }
          : {}),
        ...(field === 'locations' ? { type: previous[index]?.type ?? 'location' } : {}),
        value: term,
        origin: 'inferred',
        query_span: null,
        confidence: 1,
      };
    });
    resolution[field] = entityType
      ? [...resolution[field].filter((item) => item.type !== entityType), ...updated]
      : updated;
  }
  return JSON.stringify(resolution, null, 2);
}

export function displayEndDate(exclusive: string): string {
  if (!exclusive) return '';
  const date = new Date(`${exclusive}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}

export function updateResolutionDate(
  value: string,
  field: string,
  boundary: 'start' | 'end_exclusive',
  input: string,
): string {
  const resolution = parseResolution(value);
  const period = resolution.date_windows.find((item) => item.field === field);
  if (period?.origin === 'explicit_filter') return value;
  const next = {
    ...period,
    field,
    start: period?.start ?? '',
    end_exclusive: period?.end_exclusive ?? '',
    origin: 'inferred',
    query_span: null,
    confidence: 1,
  };
  if (boundary === 'end_exclusive' && input) {
    const date = new Date(`${input}T00:00:00Z`);
    date.setUTCDate(date.getUTCDate() + 1);
    next[boundary] = date.toISOString().slice(0, 10);
  } else {
    next[boundary] = input;
  }
  resolution.date_windows = resolution.date_windows.filter((item) => item.field !== field);
  if (next.start || next.end_exclusive) resolution.date_windows.push(next);
  return JSON.stringify(resolution, null, 2);
}

export function hasValidResolutionDates(value: string): boolean {
  return parseResolution(value).date_windows.every(
    (period) =>
      /^\d{4}-\d{2}-\d{2}$/.test(period.start) &&
      /^\d{4}-\d{2}-\d{2}$/.test(period.end_exclusive) &&
      period.start < period.end_exclusive,
  );
}

export function normalizeResolution(value: string): string {
  const resolution = parseResolution(value);
  for (const field of ['incident_names', 'entities', 'locations'] as const) {
    resolution[field] = resolution[field]
      .filter((item) => item.value.trim())
      .map((item) => ({ ...item, value: item.value.trim() }));
  }
  resolution.expanded_terms = resolution.expanded_terms.map((term) => term.trim()).filter(Boolean);
  return JSON.stringify(resolution, null, 2);
}

export function getResolutionSummary(value: string) {
  const resolution = parseResolution(value);
  return [
    {
      label: '기간',
      value:
        resolution.date_windows
          .map((period) => {
            return `${period.field === 'filming_date' ? '촬영일' : '방송일'} ${period.start.replaceAll('-', '.')} ~ ${displayEndDate(period.end_exclusive).replaceAll('-', '.')}`;
          })
          .join(' / ') || '지정하지 않음',
    },
    {
      label: '사건·주제',
      value: resolution.incident_names.map((item) => item.value).join(', ') || '지정하지 않음',
    },
    {
      label: '인물·기관',
      value: resolution.entities.map((item) => item.value).join(', ') || '지정하지 않음',
    },
    {
      label: '장소',
      value: resolution.locations.map((item) => item.value).join(', ') || '지정하지 않음',
    },
    { label: '관련 검색어', value: resolution.expanded_terms.join(', ') || '지정하지 않음' },
  ];
}
