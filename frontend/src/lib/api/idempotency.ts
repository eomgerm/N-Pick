/** Create once per new submission; keep the key for retries of that same request. */
export function createIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}
