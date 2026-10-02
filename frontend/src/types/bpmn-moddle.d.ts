/**
 * Ambient typings for `bpmn-moddle` v9 (the package ships no declarations).
 * v9 exports a `SimpleBpmnModdle` factory as its default export. The tsconfig
 * maps "bpmn-moddle" onto this file so imports type-check.
 */
declare module 'bpmn-moddle' {
  export interface ModdleRoot {
    rootElements: ModdleElement[];
    diagrams: ModdleElement[];
    $type: string;
    $attrs: Record<string, unknown>;
  }

  export interface ModdleElement extends Record<string, any> {
    $type: string;
    $parent?: ModdleElement | null;
    $attrs?: Record<string, unknown>;
  }

  export interface FromXmlResult {
    rootElement: ModdleRoot;
    warnings: Array<Error | string>;
    references: unknown[];
    elementsById: Record<string, ModdleElement>;
  }

  export interface BpmnModdleInstance {
    fromXML(xml: string, opts?: Record<string, unknown>): Promise<FromXmlResult>;
    toXML(
      rootElement: unknown,
      opts?: { format?: boolean; preamble?: boolean },
    ): Promise<{ xml: string }>;
    create(type: string, properties?: Record<string, unknown>): ModdleElement;
  }

  /** Merge {@link packages} over the bundled BPMN meta-model and return a moddle instance. */
  const SimpleBpmnModdle: (
    packages?: Record<string, unknown>,
    options?: Record<string, unknown>,
  ) => BpmnModdleInstance;

  export default SimpleBpmnModdle;
}