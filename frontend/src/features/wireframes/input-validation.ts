import type { ClipboardEvent } from 'react';

export const SEARCH_QUERY_MIN_LENGTH = 2;
export const SEARCH_QUERY_MAX_LENGTH = 500;
export const INQUIRY_COMMENT_MAX_LENGTH = 2000;
export const CLIP_TITLE_MAX_LENGTH = 50;

// Match HTML maxLength and the backend's Java String length (UTF-16 code units).
export function validateSearchQuery(query: string): string {
  const length = query.trim().length;
  if (length < SEARCH_QUERY_MIN_LENGTH) return '검색어를 2자 이상 입력해 주세요.';
  if (length > SEARCH_QUERY_MAX_LENGTH) return '검색어는 500자 이내로 입력해 주세요.';
  return '';
}

export function validateInquiryComment(comment: string): string {
  return comment.length > INQUIRY_COMMENT_MAX_LENGTH
    ? '문의 내용은 2,000자 이내로 입력해 주세요.'
    : '';
}

// Native maxLength silently truncates pasted text. Reject the whole paste and
// keep the existing value so the user never submits a shortened sentence unknowingly.
export function rejectOversizedPaste(
  event: ClipboardEvent<HTMLInputElement | HTMLTextAreaElement>,
  maxLength: number,
  onReject: () => void,
): void {
  const input = event.currentTarget;
  const selectedLength = (input.selectionEnd ?? 0) - (input.selectionStart ?? 0);
  if (
    input.value.length - selectedLength + event.clipboardData.getData('text').length >
    maxLength
  ) {
    event.preventDefault();
    onReject();
  }
}
