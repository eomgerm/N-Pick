import { type AdditionGuardOption } from '@/features/wireframes/interpretation-edit';
import { resolutionAxisLabels } from '@/features/wireframes/review-parse-rule-api';
import styles from '@/features/wireframes/review-interpretation-editor.module.css';

interface InterpretationAdditionGuardProps {
  hasOversized: boolean;
  isBusy: boolean;
  isChoosing: boolean;
  options: AdditionGuardOption[];
  selected: AdditionGuardOption | null;
  onCancel: () => void;
  onChange: () => void;
  onSelect: (id: string) => void;
}

function scopeSentence(option: AdditionGuardOption): string {
  return `${resolutionAxisLabels[option.axis]} ‘${option.value}’가 있을 때만 새 항목을 추가합니다.`;
}

export function InterpretationAdditionGuard({
  hasOversized,
  isBusy,
  isChoosing,
  options,
  selected,
  onCancel,
  onChange,
  onSelect,
}: InterpretationAdditionGuardProps) {
  if (options.length === 0) {
    return (
      <section className={`${styles.guardControl} ${styles.guardBlocked}`}>
        <h4 className={styles.guardHeading}>새 항목을 추가할 수 없는 이유</h4>
        {hasOversized ? (
          <p className={styles.guardText}>
            원본 해석값이 적용 기준의 100자 제한을 넘어 새 항목을 추가할 수 없습니다.
          </p>
        ) : (
          <p className={styles.guardText}>
            새 항목은 같은 해석을 가진 이후 검색에도 적용됩니다. 현재 해석에는 적용 범위를 정할
            기준이 없어 추가할 수 없습니다.
          </p>
        )}
        <p className={styles.guardHelp}>기존 항목의 수정·이동·삭제는 계속할 수 있습니다.</p>
      </section>
    );
  }

  if (isChoosing) {
    const hasInferred = options.some((option) => !option.isExplicit);
    return (
      <section className={`${styles.guardControl} ${styles.guardPicker}`}>
        <h4 className={styles.guardHeading}>어떤 해석에 추가할까요?</h4>
        <p className={styles.guardText}>선택한 값이 원본 해석에 있을 때만 새 항목을 추가합니다.</p>
        <div className={styles.guardOptions}>
          {options.map((option) => (
            <button
              aria-label={`${resolutionAxisLabels[option.axis]} ‘${option.value}’ ${
                option.isExplicit ? '명시값' : 'AI 추론값'
              }을 적용 기준으로 선택`}
              className={styles.guardOption}
              disabled={isBusy}
              key={option.id}
              onClick={() => onSelect(option.id)}
              type="button"
            >
              <span>
                <strong>{resolutionAxisLabels[option.axis]}</strong> ‘{option.value}’
              </span>
              <small data-inferred={!option.isExplicit || undefined}>
                {option.isExplicit ? '명시된 값' : 'AI 추론값'}
              </small>
            </button>
          ))}
        </div>
        {hasInferred ? (
          <p className={styles.guardWarning}>AI가 추론한 값은 다음 검색에서 달라질 수 있습니다.</p>
        ) : null}
        <button className={styles.guardCancel} disabled={isBusy} onClick={onCancel} type="button">
          취소
        </button>
      </section>
    );
  }

  if (!selected) {
    return (
      <section className={styles.guardControl}>
        <div>
          <h4 className={styles.guardHeading}>새 항목을 추가하려면 적용 기준을 선택해 주세요.</h4>
          <p className={styles.guardText}>
            새 항목은 선택한 해석값이 있는 이후 검색에만 적용됩니다.
          </p>
        </div>
        <button className={styles.guardChange} disabled={isBusy} onClick={onChange} type="button">
          적용 기준 선택
        </button>
      </section>
    );
  }

  return (
    <section className={styles.guardControl}>
      <div>
        <h4 className={styles.guardHeading}>새 항목 적용 기준</h4>
        <p className={styles.guardText}>{scopeSentence(selected)}</p>
        {!selected.isExplicit ? (
          <p className={styles.guardWarning}>AI가 추론한 값은 다음 검색에서 달라질 수 있습니다.</p>
        ) : null}
      </div>
      <button className={styles.guardChange} disabled={isBusy} onClick={onChange} type="button">
        기준 변경
      </button>
    </section>
  );
}
