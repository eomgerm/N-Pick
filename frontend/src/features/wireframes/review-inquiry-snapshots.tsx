import { getFilterFacts, getSnapshotFacts } from '@/features/wireframes/review-inquiry-view';
import { ResolutionSummaryView } from '@/features/wireframes/reviewer-resolution';
import { getResolutionSummary } from '@/features/wireframes/reviewer-resolution-state';

interface FilterSnapshotProps {
  value: string | null;
}
interface SearchInterpretationProps {
  value: string | null;
}
interface SnapshotCountProps {
  label: string;
  value: string | null;
}

export function FilterSnapshot({ value }: FilterSnapshotProps) {
  const facts = getFilterFacts(value);
  return (
    <section className="rounded-2xl border border-(--line) p-5" aria-labelledby="filter-title">
      <h2 className="font-bold" id="filter-title">
        문의 당시 검색 조건
      </h2>
      {facts === null ? (
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 조건을 확인할 수 없습니다.</p>
      ) : facts.length === 0 ? (
        <p className="mt-3 text-sm text-(--muted)">직접 선택한 검색 조건이 없습니다.</p>
      ) : (
        <dl className="mt-4 grid gap-3 text-sm md:grid-cols-2">
          {facts.map((fact, index) => (
            <div className="min-w-0" key={`${fact.label}-${index}`}>
              <dt className="text-(--muted)">{fact.label}</dt>
              <dd className="mt-1 wrap-anywhere">{fact.value}</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  );
}

export function SearchInterpretation({ value }: SearchInterpretationProps) {
  if (!value) {
    return (
      <section className="rounded-2xl border border-(--line) p-5">
        <h2 className="font-bold">문의 당시 검색 해석</h2>
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 해석이 없습니다.</p>
      </section>
    );
  }
  let facts: ReturnType<typeof getResolutionSummary>;
  try {
    facts = getResolutionSummary(value);
  } catch {
    return (
      <section className="rounded-2xl border border-(--line) p-5">
        <h2 className="font-bold">문의 당시 검색 해석</h2>
        <p className="mt-3 text-sm text-(--muted)">저장된 검색 해석을 확인할 수 없습니다.</p>
      </section>
    );
  }
  return <ResolutionSummaryView title="문의 당시 검색 해석" facts={facts} />;
}

export function SnapshotCount({ label, value }: SnapshotCountProps) {
  const facts = getSnapshotFacts(value);
  return (
    <div className="rounded-xl bg-(--surface-muted) p-4">
      <dt className="text-xs font-bold text-(--muted)">{label}</dt>
      <dd className="mt-2 font-semibold">
        {facts === null ? (
          '이 형식의 기록은 아직 표시할 수 없습니다.'
        ) : facts.length === 0 ? (
          '없음'
        ) : (
          <ul className="space-y-2">
            {facts.map((fact, index) => (
              <li className="wrap-anywhere" key={index}>
                {fact.label}: {fact.value}
              </li>
            ))}
          </ul>
        )}
      </dd>
    </div>
  );
}
