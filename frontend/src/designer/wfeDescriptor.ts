/**
 * Extensions to the WFE `Definitions` model.
 *
 * <p>The `extends` keyword is the documented bpmn-io way to attach attributes
 * to *existing* BPMN types (see the `custom-meta-model` example). Every
 * `bpmn:ServiceTask` element therefore gains `wfe:kind`,
 * `wfe:connection`, ... every `bpmn:UserTask` gains `wfe:assignee`, ... and
 * moddle serialises them back as attributes on the same `<bpmn2:serviceTask>`
 * / `<bpmn2:userTask>` tag, keeping the diagram valid BPMN 2.0.
 *
 * <p>Do <em>not</em> switch these to `superClass`/custom element types: that
 * would require custom shapes and add matrix with rendering, which the engine
 * does not need.
 */
export const wfeDescriptor = {
  name: 'Wfe',
  prefix: 'wfe',
  uri: 'https://wfe.dev/schema/bpmn/wfe',
  xml: {
    tagAlias: 'lowerCase',
  },
  associations: [],
  types: [
    {
      name: 'Service',
      extends: ['bpmn:ServiceTask'],
      properties: [
        { name: 'kind', isAttr: true, type: 'String', default: 'rest' },
        { name: 'connection', isAttr: true, type: 'String' },
        { name: 'operation', isAttr: true, type: 'String' },
        { name: 'httpMethod', isAttr: true, type: 'String', default: 'GET' },
        { name: 'timeoutMs', isAttr: true, type: 'Integer', default: 10_000 },
        { name: 'retries', isAttr: true, type: 'Integer', default: 0 },
        // Opaque JSON: requestTemplate/mappings/headers. Kept as one attribute
        // for Phase 0; the Designer phase gives it a structured editor.
        { name: 'config', isAttr: true, type: 'String' },
      ],
    },
    {
      name: 'User',
      extends: ['bpmn:UserTask'],
      properties: [
        // Group codes are Keycloak groups resolved by the engine's and/or
        // candidate logic; assignee is a single user id/username.
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        // Form to render in the task inbox; the form builder lands later.
        { name: 'formKey', isAttr: true, type: 'String' },
      ],
    },
  ],
} as const;

export const WFE_NAMESPACE = wfeDescriptor.uri;

export const SERVICE_KINDS = ['rest', 'soap', 'message'] as const;
export type ServiceKind = (typeof SERVICE_KINDS)[number];

export interface WfeServiceConfig {
  kind?: ServiceKind;
  connection?: string;
  operation?: string;
  httpMethod?: string;
  timeoutMs?: number;
  retries?: number;
  requestTemplate?: string;
  mappings?: Record<string, string>;
  headers?: Record<string, string>;
}

/** Reads the wfe config off a service-task business object. */
export function readWfeConfig(businessObject: Record<string, unknown> | null): WfeServiceConfig {
  if (!businessObject) return {};
  const config: WfeServiceConfig = {
    kind: (businessObject['wfe:kind'] as ServiceKind) ?? 'rest',
    connection: businessObject['wfe:connection'] as string | undefined,
    operation: businessObject['wfe:operation'] as string | undefined,
    httpMethod: (businessObject['wfe:httpMethod'] as string | undefined) ?? 'GET',
    timeoutMs: (businessObject['wfe:timeoutMs'] as number | undefined) ?? 10_000,
    retries: (businessObject['wfe:retries'] as number | undefined) ?? 0,
  };
  const blob = businessObject['wfe:config'] as string | undefined;
  if (blob) {
    try {
      const parsed = JSON.parse(blob) as Record<string, unknown>;
      config.requestTemplate = parsed.requestTemplate as string | undefined;
      config.mappings = parsed.mappings as Record<string, string> | undefined;
      config.headers = parsed.headers as Record<string, string> | undefined;
    } catch {
      // keep the raw blob; the inspector will surface the parse error.
    }
  }
  return config;
}

/** Builds the moddle-compatible update payload for a service task. */
export function serviceProperties(config: WfeServiceConfig): Record<string, unknown> {
  const blob: Record<string, unknown> = {};
  if (config.requestTemplate) blob.requestTemplate = config.requestTemplate;
  if (config.mappings) blob.mappings = config.mappings;
  if (config.headers) blob.headers = config.headers;
  return {
    'wfe:kind': config.kind ?? 'rest',
    'wfe:connection': config.connection ?? '',
    'wfe:operation': config.operation ?? '',
    'wfe:httpMethod': config.httpMethod ?? 'GET',
    'wfe:timeoutMs': config.timeoutMs ?? 10_000,
    'wfe:retries': config.retries ?? 0,
    'wfe:config': Object.keys(blob).length ? JSON.stringify(blob) : undefined,
  };
}

export interface WfeUserConfig {
  /** Single user id/username the task is assigned to. */
  assignee?: string;
  /** Comma-separated group codes; any member may claim the task. */
  candidateGroups?: string;
  /** Form to render in the task inbox. */
  formKey?: string;
}

/** Reads the wfe user-task config off a user-task business object. */
export function readWfeUserConfig(businessObject: Record<string, unknown> | null): WfeUserConfig {
  if (!businessObject) return {};
  return {
    assignee: (businessObject['wfe:assignee'] as string | undefined) || undefined,
    candidateGroups: (businessObject['wfe:candidateGroups'] as string | undefined) || undefined,
    formKey: (businessObject['wfe:formKey'] as string | undefined) || undefined,
  };
}

/** Builds the moddle-compatible update payload for a user task. */
export function userProperties(config: WfeUserConfig): Record<string, unknown> {
  return {
    'wfe:assignee': config.assignee ?? '',
    'wfe:candidateGroups': config.candidateGroups ?? '',
    'wfe:formKey': config.formKey ?? '',
  };
}