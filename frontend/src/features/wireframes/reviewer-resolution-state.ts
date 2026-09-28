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

export function parseResolution(value: string): Resolution {
  return JSON.parse(value) as Resolution;
}

export function displayEndDate(exclusive: string): string {
  if (!exclusive) return '';
  const date = new Date(`${exclusive}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
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
