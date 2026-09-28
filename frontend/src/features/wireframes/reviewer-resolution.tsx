import { Sparkles } from 'lucide-react';

import styles from '@/features/wireframes/reviewer-resolution.module.css';
import {
  getResolutionSummary,
  displayEndDate,
  parseResolution,
  updateResolutionDate,
  updateResolutionTerms,
  type ResolutionTermField,
} from '@/features/wireframes/reviewer-resolution-state';

const termFields: {
  key: ResolutionTermField;
  label: string;
  placeholder: string;
  entityType?: 'person' | 'organization';
}[] = [
  { key: 'incident_names', label: '사건·주제', placeholder: '예: 추석, 태풍 힌남노' },
  { key: 'entities', label: '인물', placeholder: '찾으려는 인물의 이름', entityType: 'person' },
  { key: 'entities', label: '기관', placeholder: '예: 한국도로공사', entityType: 'organization' },
  { key: 'locations', label: '장소', placeholder: '예: 경부고속도로' },
  { key: 'expanded_terms', label: '관련 검색어', placeholder: '예: 귀성 차량, 고속도로 정체' },
];

interface ResolutionSummaryProps {
  value: string;
  title?: string;
}

export function ResolutionSummary({
  value,
  title = '검색어를 이렇게 이해했어요',
}: ResolutionSummaryProps) {
  return (
    <section className={styles.summary} aria-label={title}>
      <h3>
        <Sparkles aria-hidden="true" />
        {title}
      </h3>
      <p>검색을 돕기 위한 해석입니다. 실제 영상 정보와 다를 수 있어요.</p>
      <dl>
        {getResolutionSummary(value).map(({ label, value: text }) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{text}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

interface ResolutionEditorProps {
  value: string;
  error?: string;
  lockedDateFields: string[];
  onChange: (value: string) => void;
}

export function ResolutionEditor({
  value,
  error,
  lockedDateFields,
  onChange,
}: ResolutionEditorProps) {
  const resolution = parseResolution(value);
  return (
    <fieldset className={styles.editor} aria-describedby={error ? 'patch-error' : 'patch-hint'}>
      <legend>검색어의 의미를 바로잡아 주세요</legend>
      <p id="patch-hint">
        여러 항목은 쉼표로 구분해 주세요. 문의자가 선택한 기간과 필터는 그대로 유지됩니다.
      </p>
      <div className={styles.fields}>
        {termFields.map(({ key, label, placeholder, entityType }) => (
          <label key={label}>
            <span>{label}</span>
            <input
              value={
                key === 'expanded_terms'
                  ? resolution[key].join(',')
                  : resolution[key]
                      .filter((item) => !entityType || item.type === entityType)
                      .map((item) => item.value)
                      .join(',')
              }
              placeholder={placeholder}
              onChange={(event) =>
                onChange(updateResolutionTerms(value, key, event.target.value, entityType))
              }
            />
          </label>
        ))}
      </div>
      {(
        [
          { field: 'broadcast_date', label: '방송일' },
          { field: 'filming_date', label: '촬영일' },
        ] as const
      ).map(({ field, label }) => {
        const period = resolution.date_windows.find((item) => item.field === field);
        const isLocked = lockedDateFields.includes(field) || period?.origin === 'explicit_filter';
        return (
          <fieldset className={styles.dates} key={field}>
            <legend>
              {label}
              {isLocked ? ' · 문의자가 선택한 기간' : ' · 선택 사항'}
            </legend>
            <div className={styles.fields}>
              <label>
                <span>{label} 시작</span>
                <input
                  type="date"
                  readOnly={isLocked}
                  value={period?.start ?? ''}
                  aria-invalid={Boolean(error) && !isLocked}
                  aria-describedby={error ? 'patch-error' : undefined}
                  onChange={(event) =>
                    onChange(updateResolutionDate(value, field, 'start', event.target.value))
                  }
                />
              </label>
              <label>
                <span>{label} 종료</span>
                <input
                  type="date"
                  readOnly={isLocked}
                  value={displayEndDate(period?.end_exclusive ?? '')}
                  aria-invalid={Boolean(error) && !isLocked}
                  aria-describedby={error ? 'patch-error' : undefined}
                  onChange={(event) =>
                    onChange(
                      updateResolutionDate(value, field, 'end_exclusive', event.target.value),
                    )
                  }
                />
              </label>
            </div>
          </fieldset>
        );
      })}
      {error ? (
        <p className={styles.error} id="patch-error" role="alert">
          {error}
        </p>
      ) : null}
    </fieldset>
  );
}
