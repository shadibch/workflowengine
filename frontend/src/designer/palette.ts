import type { ServiceKind } from './wfeDescriptor';

type Entry = Record<string, unknown>;
type PaletteEntries = Record<string, Entry>;
type EntriesUpdater = (entries: PaletteEntries) => PaletteEntries;

interface PaletteLike {
  registerProvider(priority: number, provider: { getPaletteEntries(): EntriesUpdater }): void;
}
interface CreateLike {
  start(event: MouseEvent, shape: unknown): void;
}
interface BpmnFactoryLike {
  /** Creates a moddle element of a known BPMN type, e.g. bpmn:ServiceTask. */
  create(type: string): Record<string, unknown>;
}
interface ElementFactoryLike {
  createShape(options: { type: string; businessObject?: unknown }): unknown;
}

/**
 * Custom palette group: the BPMN node types the engine supports.
 *
 * The palette aggregates the entries of every registered provider, so this
 * provider contributes its own `wfe` group and leaves the base `bpmn-js`
 * entries (tools, events, data objects, ...) in place. It registers with a
 * lower priority than the base provider, which fires later, so its updater
 * receives the standard entries and can also prune the ones WFE does not
 * execute yet.
 */
export default class WfePaletteProvider {
  static $inject = ['palette', 'bpmnFactory', 'create', 'elementFactory'];

  private readonly bpmnFactory: BpmnFactoryLike;
  private readonly create: CreateLike;
  private readonly elementFactory: ElementFactoryLike;

  constructor(
    palette: PaletteLike,
    bpmnFactory: BpmnFactoryLike,
    create: CreateLike,
    elementFactory: ElementFactoryLike,
  ) {
    this.bpmnFactory = bpmnFactory;
    this.create = create;
    this.elementFactory = elementFactory;
    // Priority < the base provider's 1000, so this updater runs last and sees
    // the accumulated default entries.
    palette.registerProvider(500, this);
  }

  getPaletteEntries(): EntriesUpdater {
    const entry = (type: string, className: string, title: string): Entry => {
      const action = (event: MouseEvent) => this.createElement(event, type);
      return { group: 'wfe', className, title, action: { click: action, dragstart: action } };
    };

    const serviceEntry = (kind: ServiceKind, className: string, title: string): Entry => {
      const action = (event: MouseEvent) => {
        const businessObject = this.bpmnFactory.create('bpmn:ServiceTask') as Record<string, unknown>;
        businessObject['wfe:kind'] = kind;
        businessObject.name =
          kind === 'rest' ? 'REST call' : kind === 'soap' ? 'SOAP call' : 'Send message';
        this.createElement(event, 'bpmn:ServiceTask', businessObject);
      };
      return { group: 'wfe', className, title, action: { click: action, dragstart: action } };
    };

    const wfeEntries: PaletteEntries = {
      'wfe.start-event': entry('bpmn:StartEvent', 'bpmn-icon-start-event-none', 'Start event'),
      'wfe.intermediate-throw-event': entry(
        'bpmn:IntermediateThrowEvent',
        'bpmn-icon-intermediate-event-none',
        'Intermediate throw event',
      ),
      'wfe.end-event': entry('bpmn:EndEvent', 'bpmn-icon-end-event-none', 'End event'),
      'wfe.user-task': entry('bpmn:UserTask', 'bpmn-icon-user-task', 'User task'),
      'wfe.manual-task': entry('bpmn:ManualTask', 'bpmn-icon-manual-task', 'Manual task'),
      'wfe.script-task': entry('bpmn:ScriptTask', 'bpmn-icon-script-task', 'Script task'),
      'wfe.service-task': entry('bpmn:ServiceTask', 'bpmn-icon-service-task', 'Service task'),
      'wfe.rest-service-task': serviceEntry('rest', 'bpmn-icon-service-task', 'REST service call'),
      'wfe.soap-service-task': serviceEntry('soap', 'bpmn-icon-service-task', 'SOAP service call'),
      'wfe.message-send-task': serviceEntry('message', 'bpmn-icon-send', 'Send message (Kafka)'),
      'wfe.exclusive-gateway': entry(
        'bpmn:ExclusiveGateway',
        'bpmn-icon-gateway-xor',
        'Exclusive gateway',
      ),
      'wfe.parallel-gateway': entry(
        'bpmn:ParallelGateway',
        'bpmn-icon-gateway-parallel',
        'Parallel gateway',
      ),
      'wfe.inclusive-gateway': entry(
        'bpmn:InclusiveGateway',
        'bpmn-icon-gateway-or',
        'Inclusive gateway',
      ),
      'wfe.event-based-gateway': entry(
        'bpmn:EventBasedGateway',
        'bpmn-icon-gateway-eventbased',
        'Event-based gateway',
      ),
    };

    return (entries) => {
      // Not executable yet: sub-processes need scope semantics and, on this
      // host, a second definition; the base palette entry is removed so it
      // cannot be dragged in by mistake.
      delete entries['create.subprocess-expanded'];

      Object.assign(entries, wfeEntries);
      return entries;
    };
  }

  private createElement(
    event: MouseEvent,
    type: string,
    businessObject?: Record<string, unknown>,
  ): void {
    // createShape attaches the moddle business object to the shape, so the wfe
    // attributes are already part of the model before the first drag anywhere.
    const shape = this.elementFactory.createShape(
      businessObject ? { type, businessObject } : { type },
    );
    this.create.start(event, shape);
  }
}
