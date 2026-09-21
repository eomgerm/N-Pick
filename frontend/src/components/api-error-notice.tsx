import { ApiClientError, readUserMessage } from '@/lib/api/error';

interface ApiErrorNoticeProps {
  error: unknown;
  id?: string;
  message?: string;
}

export function ApiErrorNotice({ error, id, message }: ApiErrorNoticeProps) {
  const apiError =
    error instanceof ApiClientError ? error : new ApiClientError('invalid-response', 0);

  return (
    <div
      id={id}
      role="alert"
      aria-atomic="true"
      className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-950"
    >
      <p className="font-semibold wrap-anywhere whitespace-pre-wrap">
        {readUserMessage(message) ?? apiError.message}
      </p>
      <p className="mt-3">문제가 계속되면 담당자에게 문의해 주세요.</p>
    </div>
  );
}
