'use client';

import { ArrowRight, Search, X } from 'lucide-react';
import { type FormEvent, type Ref, useRef, useState } from 'react';

import {
  SEARCH_QUERY_MAX_LENGTH,
  rejectOversizedPaste,
  validateSearchQuery,
} from '@/features/wireframes/input-validation';

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
  // form landmark 이름과 입력 필드 이름을 나눈다 — 같은 값을 쓰면 landmark 와 필드가 한 이름이 된다.
  formLabel: string;
  inputLabel: string;
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
  formLabel,
  inputLabel,
  classes,
  isDisabled = false,
  isBusy = false,
  fieldRef,
  inputId = 'scene-search',
}: SceneSearchFieldProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [pasteError, setPasteError] = useState('');
  const [isComposing, setIsComposing] = useState(false);
  const queryError = validateSearchQuery(query);
  const hintId = `${inputId}-hint`;
  const describedBy = classes.hint ? hintId : undefined;
  const hint = classes.hint ? (
    <p className={classes.hint} id={hintId} aria-live="polite">
      {pasteError ||
        (query && !isComposing ? queryError : '') ||
        `검색어 2~500자 · ${query.length}/500`}
    </p>
  ) : null;
  const validationProps = {
    maxLength: SEARCH_QUERY_MAX_LENGTH,
    'aria-invalid': Boolean(pasteError || (query && queryError)),
    onChange: (event: React.ChangeEvent<HTMLInputElement>) => {
      onQueryChange(event.target.value);
      setPasteError('');
    },
    onCompositionStart: () => setIsComposing(true),
    onCompositionEnd: () => setIsComposing(false),
    onKeyDown: (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.key === 'Enter' && event.nativeEvent.isComposing) event.preventDefault();
    },
    onPaste: (event: React.ClipboardEvent<HTMLInputElement>) =>
      rejectOversizedPaste(event, SEARCH_QUERY_MAX_LENGTH, () =>
        setPasteError('검색어는 500자 이내로 입력해 주세요.'),
      ),
  };

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    if (isComposing || queryError || isDisabled) {
      event.preventDefault();
      return;
    }
    onSubmit(event);
  }

  if (variant === 'compact') {
    return (
      <form aria-label={formLabel} className={classes.form} onSubmit={handleSubmit} role="search">
        <div className={classes.field} ref={fieldRef}>
          <input
            aria-describedby={describedBy}
            aria-label={inputLabel}
            disabled={isDisabled}
            {...validationProps}
            placeholder={placeholder}
            value={query}
          />
          <button
            aria-label={isBusy ? '검색 중' : '검색'}
            className={classes.submitButton}
            disabled={Boolean(queryError) || isDisabled || isComposing}
            type="submit"
          >
            <ArrowRight aria-hidden="true" />
          </button>
        </div>
        {hint}
      </form>
    );
  }

  return (
    <form
      aria-busy={isBusy}
      aria-label={formLabel}
      className={classes.form}
      onSubmit={handleSubmit}
      role="search"
    >
      <div className={classes.field} ref={fieldRef}>
        <Search aria-hidden="true" className={classes.icon} />
        <label className={classes.srOnly} htmlFor={inputId}>
          {inputLabel}
        </label>
        <span className={classes.surface}>
          <input
            aria-describedby={describedBy}
            autoComplete="off"
            disabled={isDisabled}
            enterKeyHint="search"
            id={inputId}
            {...validationProps}
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
              setPasteError('');
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
          disabled={Boolean(queryError) || isDisabled || isComposing}
          type="submit"
        >
          <ArrowRight aria-hidden="true" />
        </button>
      </div>
      {hint}
      <p aria-live="polite" className={classes.srOnly}>
        {isBusy ? '검색 중입니다. 검색 결과 화면을 준비하고 있습니다.' : ''}
      </p>
    </form>
  );
}
