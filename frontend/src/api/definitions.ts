import { api, ApiError } from './http';

/**
 * Typed client for the definitions API.
 *
 * <p>Mirrors the server's records. Enum-shaped fields are unions rather than
 * `string`, so a typo in a filter is a compile error instead of a 400 at runtime.
 */

export type DefinitionStatus = 'DRAFT' | 'ACTIVE' | 'SUSPENDED' | 'ARCHIVED';
export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
export type ProblemSeverity = 'ERROR' | 'WARNING';

/** A stored validation finding; the same shape the validator returns live. */
export interface Problem {
  nodeId: string | null;
  code: string;
  severity: ProblemSeverity;
  message: string;
  path: string | null;
}

export interface Definition {
  id: number;
  key: string;
  name: string;
  description: string | null;
  category: string | null;
  status: DefinitionStatus;
  /** Token to echo back on the next save; a mismatch is a 409, not a silent overwrite. */
  draftRevision: number;
  latestPublishedVersion: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface Draft {
  key: string;
  revision: number;
  versionNo: number;
  bpmnXml: string;
  checksum: string | null;
  updatedAt: string;
}

export interface Version {
  versionNo: number;
  status: VersionStatus;
  checksum: string;
  shortChecksum: string;
  notes: string | null;
  validation: Problem[] | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface VersionDetail {
  version: Version;
  bpmnXml: string;
}

export interface ValidationReport {
  valid: boolean;
  blockingCount: number;
  warningCount: number;
  checksum: string | null;
  problems: Problem[];
}

export interface ListParams {
  q?: string;
  category?: string;
  status?: DefinitionStatus[];
  page?: number;
  size?: number;
}

export function listDefinitions(params: ListParams = {}): Promise<Page<Definition>> {
  const query = new URLSearchParams();
  if (params.q) query.set('q', params.q);
  if (params.category) query.set('category', params.category);
  // Repeated parameters: Spring binds ?status=A&status=B into a List<String>.
  params.status?.forEach((status) => query.append('status', status));
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  return api<Page<Definition>>(`/api/definitions?${query.toString()}`);
}

export function listCategories(): Promise<string[]> {
  return api<string[]>('/api/definitions/categories');
}

export function getDefinition(key: string): Promise<Definition> {
  return api<Definition>(`/api/definitions/${encodeURIComponent(key)}`);
}

export function createDefinition(input: {
  key: string;
  name: string;
  description?: string | null;
  category?: string | null;
}): Promise<Definition> {
  return api<Definition>('/api/definitions', { method: 'POST', body: JSON.stringify(input) });
}

/**
 * Partial update.
 *
 * <p>An omitted field is left alone and an empty string clears an optional field.
 * That is why this takes optionals rather than a full object: sending an empty
 * object must not blank the name.
 */
export function updateDefinition(
  key: string,
  patch: {
    name?: string;
    description?: string | null;
    category?: string | null;
    status?: DefinitionStatus;
  },
): Promise<Definition> {
  return api<Definition>(`/api/definitions/${encodeURIComponent(key)}`, {
    method: 'PATCH',
    body: JSON.stringify(patch),
  });
}

/** Archive rather than delete: history stays readable. */
export function retireDefinition(key: string): Promise<void> {
  return api<void>(`/api/definitions/${encodeURIComponent(key)}`, { method: 'DELETE' });
}

export function getDraft(key: string): Promise<Draft> {
  return api<Draft>(`/api/definitions/${encodeURIComponent(key)}/draft`);
}

export function saveDraft(key: string, bpmnXml: string, baseRevision: number): Promise<Draft> {
  return api<Draft>(`/api/definitions/${encodeURIComponent(key)}/draft`, {
    method: 'PUT',
    body: JSON.stringify({ bpmnXml, baseRevision }),
  });
}

export function validateDraft(key: string): Promise<ValidationReport> {
  return api<ValidationReport>(`/api/definitions/${encodeURIComponent(key)}/draft/validate`, {
    method: 'POST',
  });
}

export function listVersions(key: string): Promise<Version[]> {
  return api<Version[]>(`/api/definitions/${encodeURIComponent(key)}/versions`);
}

export function getVersion(key: string, versionNo: number): Promise<VersionDetail> {
  return api<VersionDetail>(`/api/definitions/${encodeURIComponent(key)}/versions/${versionNo}`);
}

/**
 * Publishes the current draft.
 *
 * <p>201 created a version, 200 means the design was already published. Both
 * return the version, so the caller does not need to distinguish them except to
 * word the confirmation.
 */
export function publish(key: string, notes?: string): Promise<Version> {
  return api<Version>(`/api/definitions/${encodeURIComponent(key)}/versions`, {
    method: 'POST',
    body: JSON.stringify({ notes: notes ?? null }),
  });
}

/** A draft race the caller lost, as the server describes it. */
export interface DraftConflict {
  kind: 'draft-stale';
  key: string;
  expectedRevision: number;
  currentRevision: number;
}

/** A taken business key, with the server's free-key suggestion. */
export interface DuplicateKey {
  kind: 'definition-key-taken';
  key: string;
  suggestion: string;
}

export type ApiConflict = DraftConflict | DuplicateKey;

function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

/**
 * Recognises the two conflicts the UI can act on.
 *
 * <p>Type is preferred over status for dispatch, because several conflicts share
 * 409 and only the code says which one this is.
 */
export function asConflict(error: unknown): ApiConflict | null {
  if (!isApiError(error)) return null;
  const code = error.get<string>('code');
  if (code === 'draft-stale') {
    return {
      kind: 'draft-stale',
      key: error.get<string>('key') ?? '',
      expectedRevision: error.get<number>('expectedRevision') ?? 0,
      currentRevision: error.get<number>('currentRevision') ?? 0,
    };
  }
  if (code === 'definition-key-taken') {
    return {
      kind: 'definition-key-taken',
      key: error.get<string>('key') ?? '',
      suggestion: error.get<string>('suggestion') ?? '',
    };
  }
  return null;
}

/** Validation findings attached to a 422 publish rejection. */
export function problemsFrom(error: unknown): Problem[] {
  return isApiError(error) ? (error.get<Problem[]>('problems') ?? []) : [];
}
