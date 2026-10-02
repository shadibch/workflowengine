import { useCallback, useEffect, useRef, useState } from 'react';
import {
  App as AntApp,
  Alert,
  Badge,
  Button,
  Drawer,
  Input,
  List,
  Modal,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CloudDownloadOutlined,
  CodeOutlined,
  ExperimentOutlined,
  HistoryOutlined,
  ReloadOutlined,
  SaveOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { ModelerPane, type ModelerHandle, type DesignerSelection } from '../designer/ModelerPane';
import { Inspector } from '../designer/inspector';
import { useAuth } from '../auth/AuthProvider';
import {
  asConflict,
  getDefinition,
  getDraft,
  getVersion,
  listVersions,
  problemsFrom,
  publish,
  saveDraft,
  validateDraft,
  type Draft,
  type Problem,
  type ValidationReport,
  type Version,
} from '../api/definitions';

const { Text, Title } = Typography;

/** How long after the last edit an autosave fires. */
const AUTOSAVE_DELAY_MS = 3000;

/**
 * The designer page: canvas, inspector, and the server-backed draft.
 *
 * <h2>Why the draft lives on the server</h2>
 * This page used to keep the diagram in local state and export it on demand,
 * which loses work on a closed tab and cannot survive two people in the same
 * process. The draft is now a database row behind a revision token, so an
 * autosave that loses a race says whose edit won instead of quietly discarding
 * one of them.
 *
 * <p>Validation is likewise the server's call: this page used to re-implement the
 * rules in TypeScript, and two implementations of "valid" inevitably disagree.
 */
export function DesignerPage() {
  const { definitionId } = useParams<{ definitionId: string }>();
  const { message } = AntApp.useApp();
  const { t } = useTranslation();
  const { hasPermission } = useAuth();

  const modelerRef = useRef<ModelerHandle | null>(null);
  // Mirrors the ref in state so the Inspector is re-rendered with a usable
  // handle; a ref alone would only take effect on the next unrelated render.
  const [modeler, setModeler] = useState<ModelerHandle | null>(null);
  const [selection, setSelection] = useState<DesignerSelection | null>(null);
  const [businessObject, setBusinessObject] = useState<Record<string, unknown> | null>(null);
  const [issues, setIssues] = useState<Problem[]>([]);
  const [issuesOpen, setIssuesOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [definitionName, setDefinitionName] = useState<string | null>(null);
  const [initialXml, setInitialXml] = useState<string>('');
  const [revision, setRevision] = useState(0);
  const [publishedVersion, setPublishedVersion] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState<{ expected: number; current: number } | null>(null);

  /** Version currently shown on the canvas, when it is not the live draft. */
  const [viewingVersion, setViewingVersion] = useState<number | null>(null);
  const [versions, setVersions] = useState<Version[]>([]);
  const [versionsOpen, setVersionsOpen] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishNotes, setPublishNotes] = useState('');

  // Autosave bookkeeping. These are refs, not state: the timer must survive
  // re-renders without restarting the countdown, and the in-flight flag must be
  // readable inside a callback that closes over an older revision.
  const autosaveTimer = useRef<number | null>(null);
  const saving = useRef(false);

  const canWrite = hasPermission('definition:write');
  const canPublish = hasPermission('definition:publish');
  const viewingHistory = viewingVersion !== null;

  const onReady = useCallback((handle: ModelerHandle) => {
    modelerRef.current = handle;
    setModeler(handle);
  }, []);

  const onSelectionChange = useCallback((next: DesignerSelection | null) => {
    setSelection(next);
    setBusinessObject(modelerRef.current?.getSelectedBusinessObject() ?? null);
  }, []);

  const refreshVersions = useCallback(async () => {
    if (!definitionId) return;
    try {
      const next = await listVersions(definitionId);
      setVersions(next);
      setPublishedVersion(next.find((version) => version.status === 'PUBLISHED')?.versionNo ?? null);
    } catch {
      // History is supplementary; its absence must not block editing.
      setVersions([]);
    }
  }, [definitionId]);

  /** Loads the server draft into the canvas and rebases local state onto it. */
  const loadDraft = useCallback(
    async (options: { intoCanvas: boolean }) => {
      if (!definitionId) return;
      const draft: Draft = await getDraft(definitionId);
      setRevision(draft.revision);
      setDirty(false);
      setConflict(null);
      setViewingVersion(null);
      if (options.intoCanvas) {
        // The modeler is already on screen: push the new document onto the
        // canvas directly.
        await modelerRef.current?.importXml(draft.bpmnXml);
      } else {
        // First load: hand the document to the modeler as it mounts. Without
        // this the canvas (and its palette) never renders.
        setInitialXml(draft.bpmnXml);
      }
    },
    [definitionId],
  );

  // Initial load: header metadata and the draft document itself.
  useEffect(() => {
    if (!definitionId) return;
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    setInitialXml('');
    setViewingVersion(null);
    void (async () => {
      try {
        const [definition] = await Promise.all([getDefinition(definitionId), loadDraft({ intoCanvas: false })]);
        if (cancelled) return;
        setDefinitionName(definition.name);
      } catch (error) {
        if (cancelled) return;
        setLoadError(error instanceof Error ? error.message : t('common.error'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    void refreshVersions();
    return () => {
      cancelled = true;
    };
  }, [definitionId, loadDraft, refreshVersions, t]);

  const cancelAutosave = useCallback(() => {
    if (autosaveTimer.current !== null) {
      window.clearTimeout(autosaveTimer.current);
      autosaveTimer.current = null;
    }
  }, []);

  /**
   * Writes the canvas to the server.
   *
   * <p>Returns whether the save actually landed, so callers can avoid reporting
   * success for a write that was rejected or skipped.
   */
  const persist = useCallback(
    async (options: { silent?: boolean } = {}): Promise<boolean> => {
      const handle = modelerRef.current;
      if (!handle || !definitionId || saving.current || viewingHistory) return false;
      saving.current = true;
      try {
        const xml = await handle.exportXml();
        const saved = await saveDraft(definitionId, xml, revision);
        setRevision(saved.revision);
        setDirty(false);
        setConflict(null);
        if (!options.silent) {
          void message.success(t('designer.savedAt', { revision: saved.revision }));
        }
        return true;
      } catch (error) {
        const lost = asConflict(error);
        if (lost?.kind === 'draft-stale') {
          // Do not overwrite the winner. The local document stays on the canvas
          // so it can be exported or re-applied after a deliberate reload.
          setConflict({ expected: lost.expectedRevision, current: lost.currentRevision });
          cancelAutosave();
          if (!options.silent) {
            void message.warning(t('designer.conflict'));
          }
        } else if (!options.silent) {
          void message.error(error instanceof Error ? error.message : t('common.error'));
        }
        return false;
      } finally {
        saving.current = false;
      }
    },
    [cancelAutosave, definitionId, message, revision, t, viewingHistory],
  );

  const scheduleAutosave = useCallback(() => {
    setDirty(true);
    cancelAutosave();
    autosaveTimer.current = window.setTimeout(() => {
      void persist({ silent: true });
    }, AUTOSAVE_DELAY_MS);
  }, [cancelAutosave, persist]);

  // Warn before leaving with unsaved edits. Browsers decide whether to honour
  // this, and cannot be made to, which is why autosave exists as well.
  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const exportXml = useCallback(async () => {
    const modeler = modelerRef.current;
    if (!modeler) {
      void message.warning(t('designer.notLoaded'));
      return;
    }
    const xml = await modeler.exportXml();
    const url = URL.createObjectURL(new Blob([xml], { type: 'application/xml' }));
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${definitionId ?? 'process'}.bpmn`;
    anchor.click();
    URL.revokeObjectURL(url);
  }, [definitionId, message, t]);

  const copyXml = useCallback(async () => {
    const modeler = modelerRef.current;
    if (!modeler) {
      void message.warning(t('designer.notLoaded'));
      return;
    }
    await navigator.clipboard.writeText(await modeler.exportXml());
    void message.success(t('designer.copied'));
  }, [message, t]);

  /** Server-side validation: the same rules the publish endpoint applies. */
  const validate = useCallback(async () => {
    if (!definitionId || viewingHistory) return;
    setBusy(true);
    try {
      // Validate what is on the server, so the answer describes what would be
      // published rather than an unsaved local edit.
      if (canWrite && dirty) {
        if (!(await persist({ silent: true }))) return;
      }
      const report: ValidationReport = await validateDraft(definitionId);
      setIssues(report.problems);
      setIssuesOpen(true);
      if (report.valid) {
        void message.success(
          report.warningCount > 0
            ? t('designer.validationPassedWithWarnings', { count: report.warningCount })
            : t('designer.validationPassed'),
        );
      } else {
        void message.warning(t('designer.validationFailed', { count: report.blockingCount }));
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : t('common.error'));
    } finally {
      setBusy(false);
    }
  }, [canWrite, definitionId, dirty, message, persist, t, viewingHistory]);

  const doPublish = useCallback(async () => {
    if (!definitionId) return;
    setBusy(true);
    cancelAutosave();
    try {
      // Publishing validates what is stored, so unsaved canvas edits must land
      // first or the user would publish the previous diagram by accident.
      if (canWrite && dirty) {
        if (!(await persist({ silent: true }))) return;
      }
      const version = await publish(definitionId, publishNotes.trim() || undefined);
      setPublishOpen(false);
      setPublishNotes('');
      // Publishing opens a fresh draft on the server, so the revision this canvas
      // was based on no longer exists: reload rather than guess.
      await loadDraft({ intoCanvas: true });
      await refreshVersions();
      void message.success(
        version.status === 'PUBLISHED'
          ? t('designer.published', { version: version.versionNo })
          : t('designer.alreadyPublished', { version: version.versionNo }),
      );
    } catch (error) {
      const found = problemsFrom(error);
      if (found.length > 0) {
        setIssues(found);
        setIssuesOpen(true);
        void message.error(t('designer.validationFailed', { count: found.length }));
      } else {
        void message.error(error instanceof Error ? error.message : t('common.error'));
      }
    } finally {
      setBusy(false);
    }
  }, [canWrite, cancelAutosave, definitionId, dirty, loadDraft, message, persist, publishNotes, refreshVersions, t]);

  /**
   * Opens a published version read-only.
   *
   * <p>Saving is blocked while an old version is on the canvas: writing a
   * historic document back over the live draft would be indistinguishable, later,
   * from an intentional revert.
   */
  const openVersion = useCallback(
    async (version: Version) => {
      if (!definitionId) return;
      try {
        const detail = await getVersion(definitionId, version.versionNo);
        await modelerRef.current?.importXml(detail.bpmnXml);
        cancelAutosave();
        setViewingVersion(version.versionNo);
        setDirty(false);
      } catch (error) {
        void message.error(error instanceof Error ? error.message : t('common.error'));
      }
    },
    [cancelAutosave, definitionId, message, t],
  );

  const backToDraft = useCallback(async () => {
    setBusy(true);
    try {
      await loadDraft({ intoCanvas: true });
    } catch (error) {
      void message.error(error instanceof Error ? error.message : t('common.error'));
    } finally {
      setBusy(false);
    }
  }, [loadDraft, message, t]);

  /** Reloads after a lost race. The local edits are overwritten, so say so. */
  const resolveConflict = useCallback(() => {
    setConflict(null);
    void backToDraft();
  }, [backToDraft]);

  if (!definitionId) {
    return <Alert type="info" message={t('designer.noDefinition')} />;
  }

  return (
    <div className="designer">
      <div className="designer__toolbar">
        <Title level={4} className="designer__title">
          {definitionName ?? definitionId}
          {publishedVersion !== null && (
            <Tag color="green" style={{ marginInlineStart: 8 }}>
              {t('designer.publishedTag', { version: publishedVersion })}
            </Tag>
          )}
        </Title>
        <Space wrap>
          <Button onClick={() => void validate()} loading={busy} disabled={viewingHistory || loadError !== null}>
            {t('designer.validate')}
          </Button>
          <Button
            icon={<HistoryOutlined />}
            onClick={() => {
              void refreshVersions();
              setVersionsOpen((open) => !open);
            }}
          >
            {t('designer.history')}
          </Button>
          <Tooltip title={t('designer.export')}>
            <Button icon={<CloudDownloadOutlined />} onClick={() => void exportXml()} />
          </Tooltip>
          <Tooltip title={t('designer.copyXml')}>
            <Button icon={<CodeOutlined />} onClick={() => void copyXml()} />
          </Tooltip>
          <Tooltip title={t('designer.simulate')}>
            <Button icon={<ExperimentOutlined />} disabled>
              {t('designer.simulate')}
            </Button>
          </Tooltip>
          {canWrite && (
            <Button
              icon={<SaveOutlined />}
              disabled={viewingHistory || loadError !== null}
              onClick={() => void persist()}
            >
              {t('designer.save')}
            </Button>
          )}
          <Button
            type="primary"
            icon={<SendOutlined />}
            disabled={!canPublish || viewingHistory || loadError !== null}
            onClick={() => setPublishOpen(true)}
          >
            {t('designer.publish')}
          </Button>
        </Space>
      </div>

      <div className="designer__banners">
        {viewingHistory && (
          <Alert
            type="info"
            showIcon
            closable
            onClose={() => void backToDraft()}
            message={t('designer.viewingVersion', { version: viewingVersion })}
            action={
              <Button size="small" icon={<ReloadOutlined />} onClick={() => void backToDraft()}>
                {t('designer.backToDraft')}
              </Button>
            }
          />
        )}
        {conflict && (
          <Alert
            type="warning"
            showIcon
            message={t('designer.conflictTitle')}
            description={t('designer.conflictBody', {
              expected: conflict.expected,
              current: conflict.current,
            })}
            action={
              <Button size="small" icon={<ReloadOutlined />} onClick={resolveConflict}>
                {t('designer.reload')}
              </Button>
            }
          />
        )}
        {loadError && (
          <Alert type="error" showIcon message={t('designer.loadFailed')} description={loadError} />
        )}
      </div>

      <div className="designer__body">
        <div className="designer__canvas-wrap">
          {busy && (
            <div className="designer__spinner">
              <Spin />
            </div>
          )}
          {!loading && initialXml && (
            // Keyed by definition so navigating between designs remounts the
            // modeler: it imports its initial document once, on mount.
            <ModelerPane
              key={definitionId}
              initialXml={initialXml}
              onReady={onReady}
              onSelectionChange={onSelectionChange}
              onChanged={scheduleAutosave}
              onImportError={(error, warnings) => {
                if (error) void message.error(t('designer.loadFailed'));
                else if (warnings.length > 0) void message.warning(t('designer.importWarnings', { count: warnings.length }));
              }}
            />
          )}
        </div>

        <Inspector modeler={modeler} selection={selection} businessObject={businessObject} />
      </div>

      <Drawer
        title={t('designer.validationTitle')}
        open={issuesOpen}
        onClose={() => setIssuesOpen(false)}
        width={420}
        extra={
          <Badge
            count={issues.filter((issue) => issue.severity === 'ERROR').length}
            style={{ backgroundColor: '#cf1322' }}
          />
        }
      >
        {issues.length === 0 ? (
          <Text type="secondary">{t('designer.noIssues')}</Text>
        ) : (
          <List
            dataSource={issues}
            renderItem={(issue) => (
              <List.Item className={`validation-issue validation-issue--${issue.severity.toLowerCase()}`}>
                <div>
                  <Tag color={issue.severity === 'ERROR' ? 'red' : 'orange'}>{issue.code}</Tag>
                  {/* The server message is English and describes the caller's own
                      diagram, so it is shown as a fallback; codes are localised below. */}
                  <Text>{describe(issue, t)}</Text>
                  {issue.nodeId && (
                    <div>
                      <Text type="secondary" code>
                        {issue.nodeId}
                      </Text>
                    </div>
                  )}
                </div>
              </List.Item>
            )}
          />
        )}
      </Drawer>

      <Drawer
        title={t('designer.history')}
        open={versionsOpen}
        onClose={() => setVersionsOpen(false)}
        width={460}
      >
        <VersionHistory
          versions={versions}
          onOpen={(version) => void openVersion(version)}
          openLabel={t('designer.openVersion')}
        />
      </Drawer>

      <Modal
        open={publishOpen}
        title={t('designer.publish')}
        okText={t('designer.publish')}
        cancelText={t('common.cancel')}
        confirmLoading={busy}
        onCancel={() => setPublishOpen(false)}
        onOk={() => void doPublish()}
      >
        <Text type="secondary">{t('designer.publishHint')}</Text>
        <Input.TextArea
          rows={3}
          value={publishNotes}
          onChange={(event) => setPublishNotes(event.target.value)}
          placeholder={t('designer.notesHint')}
          style={{ marginTop: 12 }}
        />
      </Modal>
    </div>
  );
}

/**
 * Localised text for a validation finding.
 *
 * <p>The server's message is the fallback rather than the primary string: codes
 * are stable and translatable, messages are written for logs and API clients.
 */
function describe(issue: Problem, t: (key: string, options?: Record<string, unknown>) => string): string {
  const key = `designer.problems.${issue.code}`;
  const localised = t(key);
  return localised === key ? issue.message : localised;
}

function VersionHistory({
  versions,
  onOpen,
  openLabel,
}: {
  versions: Version[];
  onOpen: (version: Version) => void;
  openLabel: string;
}) {
  return (
    <List
      dataSource={versions}
      locale={{ emptyText: <Text type="secondary">—</Text> }}
      renderItem={(version) => (
        <List.Item
          actions={[
            <Button key="open" type="link" onClick={() => onOpen(version)}>
              {openLabel}
            </Button>,
          ]}
        >
          <List.Item.Meta
            title={
              <Space>
                <Text strong>v{version.versionNo}</Text>
                <Tag color={version.status === 'PUBLISHED' ? 'green' : version.status === 'DRAFT' ? 'blue' : 'default'}>
                  {version.status}
                </Tag>
                <Text type="secondary" code>
                  {version.shortChecksum}
                </Text>
              </Space>
            }
            description={
              <>
                {version.notes && <div>{version.notes}</div>}
                <Text type="secondary">
                  {new Date(version.publishedAt ?? version.createdAt).toLocaleString()}
                </Text>
              </>
            }
          />
        </List.Item>
      )}
    />
  );
}
