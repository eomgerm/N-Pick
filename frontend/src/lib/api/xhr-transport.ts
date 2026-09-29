export interface UploadProgressEvent {
  loaded: number;
  total: number;
}

// fetch 는 업로드 진행률을 주지 않는다. 진행률이 필요한 요청(영상 등록)만 XHR 로 보내고, 결과를
// Response 로 감싸 fetchJson 의 나머지 처리(CSRF·오류 해석·401 재시도·로그)를 그대로 태운다
// (S15P21A501-325). 실패 모양도 fetch 와 맞춘다 — 네트워크 오류는 TypeError, 취소는 AbortError.
export function sendWithUploadProgress(
  url: string | URL,
  options: RequestInit,
  onUploadProgress: (event: UploadProgressEvent) => void,
): Promise<Response> {
  return new Promise((resolve, reject) => {
    const signal = options.signal ?? undefined;
    if (signal?.aborted) {
      reject(new DOMException('The operation was aborted.', 'AbortError'));
      return;
    }
    const xhr = new XMLHttpRequest();
    xhr.open((options.method ?? 'GET').toUpperCase(), String(url));
    xhr.withCredentials = options.credentials === 'include';
    new Headers(options.headers).forEach((value, name) => xhr.setRequestHeader(name, value));
    xhr.upload.onprogress = (event) =>
      onUploadProgress({ loaded: event.loaded, total: event.total });

    const onAbort = () => xhr.abort();
    signal?.addEventListener('abort', onAbort, { once: true });
    const settle = () => signal?.removeEventListener('abort', onAbort);

    xhr.onload = () => {
      settle();
      // XHR 은 리다이렉트를 막을 수 없다. 다른 곳으로 옮겨진 응답은 fetch 의 redirect: 'error' 처럼 버린다.
      if (xhr.status < 200 || (xhr.responseURL && xhr.responseURL !== String(url))) {
        reject(new TypeError('Upload response was redirected or incomplete.'));
        return;
      }
      const headers = new Headers();
      for (const line of xhr
        .getAllResponseHeaders()
        .trim()
        .split(/[\r\n]+/)) {
        const index = line.indexOf(':');
        if (index > 0) headers.append(line.slice(0, index).trim(), line.slice(index + 1).trim());
      }
      const bodyless = xhr.status === 204 || xhr.status === 205;
      resolve(new Response(bodyless ? null : xhr.responseText, { status: xhr.status, headers }));
    };
    xhr.onerror = () => {
      settle();
      reject(new TypeError('Network request failed.'));
    };
    xhr.onabort = () => {
      settle();
      reject(new DOMException('The operation was aborted.', 'AbortError'));
    };
    xhr.send((options.body ?? null) as XMLHttpRequestBodyInit | null);
  });
}
