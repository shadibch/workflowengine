import { useEffect, useRef } from 'react';
import BpmnModeler from 'bpmn-js/lib/Modeler';

import 'bpmn-js/dist/assets/diagram-js.css';
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css';

import { wfeDescriptor } from './wfeDescriptor';
import WfePaletteProvider from './palette';

export interface DesignerSelection {
  id: string;
  type: string;
}

export interface ImportWarnings {
  warnings: string[];
}

export interface ModelerHandle {
  importXml: (xml: string) => Promise<ImportWarnings>;
  exportXml: () => Promise<string>;
  getSelection: () => DesignerSelection | null;
  getSelectedBusinessObject: () => Record<string, unknown> | null;
  applyProperties: (id: string, properties: Record<string, unknown>) => void;
  destroy: () => void;
}

export interface ModelerPaneProps {
  initialXml: string;
  onReady: (handle: ModelerHandle) => void;
  onSelectionChange: (selection: DesignerSelection | null) => void;
  /**
   * Fired whenever the command stack changes, whether from a palette drag or an
   * inspector edit. The page uses it to schedule an autosave; it says nothing
   * about whether the change was an improvement.
   */
  onChanged?: () => void;
  onImportError?: (error: unknown, warnings: string[]) => void;
}

/**
 * Owns the bpmn-js modeler lifecycle. Everything the outside world may do is
 * funnelled through {@link ModelerHandle} so page components never touch
 * diagram-js globals directly. Service typings come straight from
 * `modeler.get(...)`, which bpmn-js types as `any`.
 */
export function ModelerPane({
  initialXml,
  onReady,
  onSelectionChange,
  onChanged,
  onImportError,
}: ModelerPaneProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  // The modeler is created once, so it cannot close over the latest callbacks
  // directly; these refs keep the long-lived listeners current without forcing
  // the modeler to be rebuilt.
  const changedRef = useRef(onChanged);
  changedRef.current = onChanged;
  const selectionRef = useRef(onSelectionChange);
  selectionRef.current = onSelectionChange;

  // Create the modeler once per mount. StrictMode in dev mounts twice, so we
  // destroy on unmount and let the effect re-create it.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const modeler = new BpmnModeler({
      container,
      moddleExtensions: { wfe: wfeDescriptor },
      additionalModules: [
        {
          __init__: ['wfePaletteProvider'],
          wfePaletteProvider: ['type', WfePaletteProvider],
        },
      ],
    });

    const getSelectionService = () => modeler.get('selection') as { get(): unknown[] };
    const getRegistry = () => modeler.get('elementRegistry') as { get(id: string): unknown };

    // A programmatic import replaces the document; it is not an edit the user
    // made. Without this guard, loading a diagram — or viewing a historic
    // version — would look like a change and the page would autosave it.
    let importing = false;

    modeler.on('selection.changed', (event: { newSelection: unknown[] }) => {
      const raw = (event.newSelection as Array<{ id: string; type: string }>)[0];
      selectionRef.current(raw ? { id: raw.id, type: raw.type } : null);
    });

    modeler.on('commandStack.changed', () => {
      if (!importing) changedRef.current?.();
    });

    const handle: ModelerHandle = {
      async importXml(xml) {
        importing = true;
        try {
          const result = await modeler.importXML(xml);
          const canvas = modeler.get('canvas') as { zoom(level: string): void };
          canvas.zoom('fit-viewport');
          const warnings = (result?.warnings ?? []).map((w: unknown) =>
            w instanceof Error ? w.message : String(w),
          );
          return { warnings };
        } finally {
          importing = false;
        }
      },
      async exportXml() {
        const { xml } = await modeler.saveXML({ format: true });
        return xml ?? '';
      },
      getSelection() {
        const selection = getSelectionService().get()[0] as { id: string; type: string } | undefined;
        if (!selection) return null;
        return { id: selection.id, type: selection.type };
      },
      getSelectedBusinessObject() {
        const selection = getSelectionService().get()[0] as
          | { businessObject?: Record<string, unknown> }
          | undefined;
        return selection?.businessObject ?? null;
      },
      applyProperties(id, properties) {
        const modeling = modeler.get('modeling') as {
          updateProperties(element: unknown, props: Record<string, unknown>): void;
        };
        const element = getRegistry().get(id);
        if (!element) return;
        modeling.updateProperties(element, properties);
      },
      destroy() {
        modeler.destroy();
      },
    };

    onReady(handle);

    // Import the initial XML. If it fails we let the page show the error and
    // still expose the blank canvas.
    importing = true;
    modeler
      .importXML(initialXml)
      .then((result) => {
        const canvas = modeler.get('canvas') as { zoom(level: string): void };
        canvas.zoom('fit-viewport');
        const warnings = (result?.warnings ?? []).map((w: unknown) =>
          w instanceof Error ? w.message : String(w),
        );
        if (warnings.length > 0) {
          onImportError?.(null, warnings);
        }
      })
      .catch((error: unknown) => {
        onImportError?.(error, []);
      })
      .finally(() => {
        importing = false;
      });

    return () => {
      handle.destroy();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return <div className="modeler-canvas" ref={containerRef} data-testid="modeler-canvas" />;
}