import type { ApiError } from './types';

/** Thrown for non-2xx responses; carries the parsed API error body when present. */
export class HttpError extends Error {
  status: number;
  body?: ApiError;
  constructor(status: number, message: string, body?: ApiError) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let parsed: ApiError | undefined;
    try {
      parsed = (await res.json()) as ApiError;
    } catch {
      parsed = undefined;
    }
    throw new HttpError(res.status, parsed?.message ?? `Request failed (${res.status})`, parsed);
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

/** Builds a query string from defined, non-empty params. */
export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') {
      sp.set(k, String(v));
    }
  }
  const s = sp.toString();
  return s ? `?${s}` : '';
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body: unknown) => request<T>('POST', path, body),
  patch: <T>(path: string, body: unknown) => request<T>('PATCH', path, body),
};
