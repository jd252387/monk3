export type QueryEndpoint = 'parse' | 'search';

export interface QueryResult {
  ok: boolean;
  status: number;
  /** Client-measured round-trip time in milliseconds. */
  ms: number;
  /** Raw response body (success payload or error JSON). */
  text: string;
}

/**
 * POSTs the raw editor text to `/queries/{endpoint}` and measures the round-trip
 * time client-side (neither endpoint returns server-side timing).
 */
export async function runQuery(endpoint: QueryEndpoint, body: string): Promise<QueryResult> {
  const start = performance.now();
  const res = await fetch(`/queries/${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
  });
  const text = await res.text();
  return { ok: res.ok, status: res.status, ms: performance.now() - start, text };
}
