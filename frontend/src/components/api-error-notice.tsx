import { ApiClientError } from '@/lib/api/error';

interface ApiErrorNoticeProps {
  error: unknown;
  id?: string;
}

export function ApiErrorNotice({ error, id }: ApiErrorNoticeProps) {
  const apiError =
    error instanceof ApiClientError ? error : new ApiClientError('invalid-response', 0);

  return (
    <div
      id={id}
      role="alert"
      aria-atomic="true"
      className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-950"
    >
      <p className="font-semibold wrap-anywhere whitespace-pre-wrap">{apiError.message}</p>
      <dl className="mt-3 flex flex-wrap gap-x-6 gap-y-2 text-xs">
        <div className="min-w-0">
          <dt className="font-medium">오류 코드</dt>
          <dd className="mt-1 wrap-anywhere">{apiError.code}</dd>
        </div>
        <div className="min-w-0">
          <dt className="font-medium">요청 ID</dt>
          <dd className="mt-1 wrap-anywhere">{apiError.requestId ?? '제공되지 않음'}</dd>
        </div>
      </dl>
      <p className="mt-3">문제가 계속되면 오류 코드와 요청 ID를 담당자에게 전달해 주세요.</p>
    </div>
  );
}
