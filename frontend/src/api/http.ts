import { message } from 'antd';
import { getAccessToken } from '../app/store';

/**
 * An RFC 9457 problem body.
 *
 * <p>The extension members are per-error: a stale draft carries
 * {@code currentRevision} so the client can rebase without a second request, and
 * a publish rejection carries the full {@code problems} list. They are typed as
 * open because their shape depends on the `type` URI.
 */
export interface ProblemBody {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  requestId?: string;
  code?: string;
  [extension: string]: unknown;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    /** The parsed problem body, extensions included. */
    public readonly problem?: ProblemBody,
  ) {
    super(message);
  }

  /** A problem extension member by name. */
  get<T = unknown>(member: string): T | undefined {
    return this.problem?.[member] as T | undefined;
  }
}

/**
 * Typed fetch wrapper: attaches the bearer token from the auth store and
 * normalizes errors to Problem Detail (RFC 9457). The token is read at call
 * time, so the same module works in and out of React.
 */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getAccessToken();
  const requestId = crypto.randomUUID();

  const headers = new Headers(init.headers);
  headers.set('X-WFE-Request-Id', requestId);
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(path, { ...init, headers });

  if (response.ok) {
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  let problem: ProblemBody | undefined;
  try {
    problem = (await response.json()) as ProblemBody;
  } catch {
    // non-JSON body
  }

  if (response.status === 401) {
    // Session disappeared (silent renew failed). Bounce to the login screen,
    // which restarts the OIDC flow and lands the user back on their page.
    window.location.href = '/login';
    throw new ApiError(response.status, 'Authentication required', problem);
  }

  throw new ApiError(
    response.status,
    problem?.detail ?? `Request failed with status ${response.status}`,
    problem,
  );
}

/** Current user, resolved from the server, per endpoints that need it. */
export async function me(): Promise<Record<string, unknown>> {
  return api<Record<string, unknown>>('/api/system/me');
}

export function notifyError(error: unknown, fallback = 'Something went wrong') {
  const detail = error instanceof ApiError ? error.message : fallback;
  void message.error(detail);
}