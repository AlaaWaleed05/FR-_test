/**
 * Thin fetch wrapper for the backend's `/api/v1` surface.
 *
 * CSRF: chain 1 (`SecurityConfiguration`, `csrf(CsrfConfigurer::spa)`) sets a non-HttpOnly
 * `XSRF-TOKEN` cookie on every response through that chain, including a 401 (verified live against
 * the running backend: `curl -c` on an unauthenticated `GET /api/v1/auth/me` still returns
 * `Set-Cookie: XSRF-TOKEN=...`). Every unsafe request (POST/PUT/PATCH/DELETE) must echo that value
 * back as the `X-XSRF-TOKEN` header, or the request never reaches the controller (bare CSRF
 * rejection is a 403 with no body, confirmed live and distinct from a 401 auth failure).
 *
 * 401 handling: `setUnauthorizedHandler` lets `AuthContext` learn about a 401 from ANY call, not
 * only the ones it makes directly (e.g. a stale session discovered while `ProfileListPage` is
 * fetching), so it can drop back to the anonymous state from anywhere.
 */

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

let unauthorizedHandler: (() => void) | null = null;

/** Called once by `AuthContext` on mount. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split(';')) {
    const trimmed = part.trim();
    if (trimmed.startsWith(prefix)) {
      return decodeURIComponent(trimmed.slice(prefix.length));
    }
  }
  return null;
}

/** {@link RequestOptions} minus `formBody`, which {@link apiFetchBlob} does not implement. */
export type BlobRequestOptions = Omit<RequestOptions, 'formBody'>;

export interface RequestOptions {
  method?: string;
  body?: unknown;
  /** `application/x-www-form-urlencoded` body — Spring's `formLogin` processing URL reads
   * `request.getParameter(...)`, not a JSON body, so login cannot use `body` above. */
  formBody?: Record<string, string>;
  /** Query parameters; undefined/null/empty-string values are omitted, everything else
   * stringified. Typed loosely so any plain params object (e.g. a generated query DTO) can be
   * passed directly without an index-signature cast at the call site. */
  query?: Record<string, unknown>;
  /** A 401 from this specific call should not trigger the global handler (e.g. the boot-time
   * `/me` probe, where 401 is an expected, ordinary outcome, not a session drop). */
  suppressUnauthorizedHandler?: boolean;
}

function buildQueryString(query: RequestOptions['query']): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `?${qs}` : '';
}

/**
 * @throws ApiError for any non-2xx response, including 401/403 — callers decide what those mean
 * in context (an expected "not signed in" probe vs. a genuine mid-session drop).
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {};
  let body: string | undefined;

  if (options.formBody) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(options.formBody).toString();
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (UNSAFE_METHODS.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers['X-XSRF-TOKEN'] = token;
    }
  }

  const response = await fetch(`/api/v1${path}${buildQueryString(options.query)}`, {
    method,
    headers,
    body,
    credentials: 'same-origin',
  });

  if (response.status === 401 && !options.suppressUnauthorizedHandler) {
    unauthorizedHandler?.();
  }

  if (!response.ok) {
    let message = response.statusText;
    try {
      const text = await response.text();
      if (text) message = text;
    } catch {
      // keep statusText
    }
    throw new ApiError(response.status, message);
  }

  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/**
 * The same request plumbing, for an endpoint whose body is BYTES rather than JSON.
 *
 * Shares `apiFetch`'s CSRF header, its `same-origin` credentials and its 401 handling, because a
 * blob endpoint on this chain needs every one of them — the printed form is a POST, so it is an
 * unsafe method and the `X-XSRF-TOKEN` echo is mandatory.
 *
 * It differs in three ways, all deliberate and none of them safe to forget: the response is read
 * with `blob()`, since `apiFetch`'s `response.json()` would throw on a PDF; there is no 204 case,
 * because an endpoint returning bytes returns bytes; and it takes {@link BlobRequestOptions} rather
 * than {@link RequestOptions}, which is narrower BECAUSE this function has no `formBody` handling.
 * Accepting the wider type would have let a caller pass `formBody` and get a request with no body,
 * no `Content-Type` and no error anywhere — found at review, before any caller did.
 *
 * The duplication is real and is left in place: the two share the CSRF echo, the credentials mode,
 * the 401 handler and the error-body read, and a future change to `apiFetch`'s request contract
 * will not reach this one on its own.
 *
 * Returns the response headers alongside the bytes. The print endpoint names the stored artifact
 * in `X-Printed-Form-Artifact-Id`, and a caller that could not read it would have to make a second
 * round trip to learn what its own print became.
 */
export async function apiFetchBlob(
  path: string,
  options: BlobRequestOptions = {},
): Promise<{ blob: Blob; headers: Headers }> {
  const method = options.method ?? 'GET';
  const headers: Record<string, string> = {};
  let body: string | undefined;

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }
  if (UNSAFE_METHODS.has(method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) {
      headers['X-XSRF-TOKEN'] = token;
    }
  }

  const response = await fetch(`/api/v1${path}${buildQueryString(options.query)}`, {
    method,
    headers,
    body,
    credentials: 'same-origin',
  });

  if (response.status === 401 && !options.suppressUnauthorizedHandler) {
    unauthorizedHandler?.();
  }

  if (!response.ok) {
    let message = response.statusText;
    try {
      const text = await response.text();
      if (text) message = text;
    } catch {
      // keep statusText
    }
    throw new ApiError(response.status, message);
  }

  return { blob: await response.blob(), headers: response.headers };
}
