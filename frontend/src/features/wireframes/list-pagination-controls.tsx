'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useId, useState } from 'react';

import {
  getPageItems,
  PAGE_SIZE_OPTIONS,
  parsePageJump,
} from '@/features/wireframes/list-pagination';
import styles from '@/features/wireframes/list-pagination.module.css';

interface PageNumbersProps {
  page: number;
  totalPages: number;
  isCompact?: boolean;
  isDisabled?: boolean;
  onPageChange: (page: number) => void;
}

// 버튼 모양은 감싸는 nav 의 `.pagination button` 규칙이 정한다. 화면마다 테마가 달라 여기서 색을 정하지 않는다.
// isDisabled 는 조회·이동 중 잠금이다. disabled 를 걸면 누른 버튼의 포커스가 빠지므로 aria-disabled 로 알리고 누름만 막는다.
// 첫·끝 쪽에서 더 갈 곳이 없는 이전·다음은 그대로 disabled 다.
export function PageNumbers({
  page,
  totalPages,
  isCompact = false,
  isDisabled = false,
  onPageChange,
}: PageNumbersProps) {
  const lastPage = Math.max(totalPages, 1);

  function handlePageClick(target: number) {
    if (!isDisabled && target !== page) onPageChange(target);
  }

  return (
    <span className={styles.pageNumbers} data-compact={isCompact || undefined}>
      <button
        aria-disabled={isDisabled || undefined}
        aria-label="이전 페이지"
        disabled={page <= 1}
        onClick={() => handlePageClick(page - 1)}
        type="button"
      >
        <ChevronLeft aria-hidden="true" />
        {isCompact ? null : <span>이전</span>}
      </button>
      {getPageItems(page, lastPage).map((item, index) =>
        item === 'ellipsis' ? (
          <span aria-hidden="true" className={styles.ellipsis} key={`ellipsis-${index}`}>
            …
          </span>
        ) : (
          <button
            aria-current={item === page ? 'page' : undefined}
            aria-disabled={isDisabled || undefined}
            aria-label={`${item}페이지`}
            className={styles.pageButton}
            key={item}
            onClick={() => handlePageClick(item)}
            type="button"
          >
            {item}
          </button>
        ),
      )}
      <button
        aria-disabled={isDisabled || undefined}
        aria-label="다음 페이지"
        disabled={page >= lastPage}
        onClick={() => handlePageClick(page + 1)}
        type="button"
      >
        {isCompact ? null : <span>다음</span>}
        <ChevronRight aria-hidden="true" />
      </button>
    </span>
  );
}

interface PageJumpProps {
  totalPages: number;
  isDisabled?: boolean;
  onPageChange: (page: number) => void;
}

export function PageJump({ totalPages, isDisabled = false, onPageChange }: PageJumpProps) {
  const errorId = useId();
  const [value, setValue] = useState('');
  const [error, setError] = useState<string | null>(null);
  if (totalPages <= 1) return null;

  function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isDisabled) return;
    const result = parsePageJump(value, totalPages);
    if ('error' in result) {
      setError(result.error);
      return;
    }
    setError(null);
    setValue('');
    onPageChange(result.page);
  }

  return (
    <form className={styles.jump} noValidate onSubmit={handleSubmit}>
      <input
        aria-describedby={error ? errorId : undefined}
        aria-disabled={isDisabled || undefined}
        aria-invalid={error ? true : undefined}
        aria-label="이동할 페이지 번호"
        className={styles.jumpInput}
        inputMode="numeric"
        onChange={(event) => {
          setValue(event.target.value);
          setError(null);
        }}
        readOnly={isDisabled}
        type="text"
        value={value}
      />
      <span aria-hidden="true">/ {totalPages}</span>
      <button aria-disabled={isDisabled || undefined} type="submit">
        이동
      </button>
      {error ? (
        <p className={styles.jumpError} id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </form>
  );
}

interface PageSizeSelectProps {
  pageSize: number;
  isDisabled?: boolean;
  onPageSizeChange: (size: number) => void;
}

// 문구는 KRDS 목록 패턴의 「목록 표시 개수」를 따른다. 옵션 문구가 스스로 설명되므로 라벨은 화면에 숨긴다.
// 잠금은 PageNumbers 와 같은 이유로 aria-disabled 다. 값이 제어되므로 막힌 선택은 그대로 되돌아간다.
export function PageSizeSelect({
  pageSize,
  isDisabled = false,
  onPageSizeChange,
}: PageSizeSelectProps) {
  const id = useId();
  return (
    <span>
      <label className="sr-only" htmlFor={id}>
        목록 표시 개수
      </label>
      <select
        aria-disabled={isDisabled || undefined}
        className={styles.sizeSelect}
        id={id}
        onChange={(event) => {
          if (!isDisabled) onPageSizeChange(Number(event.target.value));
        }}
        value={pageSize}
      >
        {PAGE_SIZE_OPTIONS.map((size) => (
          <option key={size} value={size}>
            {size}개씩
          </option>
        ))}
      </select>
    </span>
  );
}
