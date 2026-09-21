'use client';

import { ArrowRight, Search, X } from 'lucide-react';
import { type FormEvent, type Ref, useRef } from 'react';

import { SEARCH_QUERY_MIN_LENGTH } from '@/features/wireframes/search-api-contract';

// 슬롯별 클래스는 호출 페이지가 자기 CSS 모듈에서 주입한다. entry.module.css(hero)와
// wireframe.module.css(compact)의 .searchForm 이 서로 다른 요소를 가리키므로, 클래스명이 아니라
// 슬롯 이름으로 받아 겉모습을 각 페이지 현행 그대로 유지한다.
export interface SceneSearchClasses {
  form: string;
  field: string;
  submitButton: string;
  icon?: string;
  surface?: string;
  clearButton?: string;
  hint?: string;
  srOnly?: string;
}

interface SceneSearchFieldProps {
  variant: 'hero' | 'compact';
  query: string;
  onQueryChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  placeholder: string;
  label: string;
  classes: SceneSearchClasses;
  isDisabled?: boolean;
  isBusy?: boolean;
  // 검색 전환 애니메이션이 측정·이동하는 필드 박스 ref. hero 는 로컬 ref, compact 는 useSearchArrival 의 ref.
  fieldRef?: Ref<HTMLDivElement>;
  inputId?: string;
}

export function SceneSearchField({
  variant,
  query,
  onQueryChange,
  onSubmit,
  placeholder,
  label,
  classes,
  isDisabled = false,
  isBusy = false,
  fieldRef,
  inputId = 'scene-search',
}: SceneSearchFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const trimmedLength = query.trim().length;
  const isBelowMinLength = trimmedLength < SEARCH_QUERY_MIN_LENGTH;
  const showMinLengthHint = trimmedLength >= 1 && isBelowMinLength;
  const hintId = `${inputId}-hint`;
  // 힌트를 입력창에 aria-describedby 로 묶어, 비활성 제출 버튼의 이유가 스크린리더에 이어지게 한다.
  const describedBy = showMinLengthHint && classes.hint ? hintId : undefined;
  const minLengthHint =
    showMinLengthHint && classes.hint ? (
      <p className={classes.hint} id={hintId} role="alert">
        검색어는 {SEARCH_QUERY_MIN_LENGTH}글자 이상 입력해 주세요.
      </p>
    ) : null;

  if (variant === 'compact') {
    return (
      <form aria-label={label} className={classes.form} onSubmit={onSubmit} role="search">
        <div className={classes.field} ref={fieldRef}>
          <input
            aria-describedby={describedBy}
            aria-label={label}
            disabled={isDisabled}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder={placeholder}
            value={query}
          />
          <button
            aria-label={isBusy ? '검색 중' : '검색'}
            className={classes.submitButton}
            disabled={isBelowMinLength || isDisabled}
            type="submit"
          >
            <ArrowRight aria-hidden="true" />
          </button>
        </div>
        {minLengthHint}
      </form>
    );
  }

  return (
    <form
      aria-busy={isBusy}
      aria-label={label}
      className={classes.form}
      onSubmit={onSubmit}
      role="search"
    >
      <div className={classes.field} ref={fieldRef}>
        <Search aria-hidden="true" className={classes.icon} />
        <label className={classes.srOnly} htmlFor={inputId}>
          {label}
        </label>
        <span className={classes.surface}>
          <input
            aria-describedby={describedBy}
            autoComplete="off"
            disabled={isDisabled}
            enterKeyHint="search"
            id={inputId}
            onChange={(event) => onQueryChange(event.target.value)}
            placeholder={placeholder}
            ref={inputRef}
            type="search"
            value={query}
          />
          <button
            aria-label="검색어 지우기"
            className={classes.clearButton}
            disabled={!query || isDisabled}
            onClick={() => {
              onQueryChange('');
              inputRef.current?.focus();
            }}
            type="button"
          >
            <X aria-hidden="true" />
          </button>
        </span>
        <button
          aria-label={isBusy ? '검색 중' : '장면 찾기'}
          className={classes.submitButton}
          disabled={isBelowMinLength || isDisabled}
          type="submit"
        >
          <ArrowRight aria-hidden="true" />
        </button>
      </div>
      {minLengthHint}
      <p aria-live="polite" className={classes.srOnly}>
        {isBusy ? '검색 중입니다. 검색 결과 화면을 준비하고 있습니다.' : ''}
      </p>
    </form>
  );
}
