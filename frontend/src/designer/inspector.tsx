import { useEffect, useMemo, useState } from 'react';
import { Button, Empty, Form, Input, InputNumber, Select, Tag, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';

import type { ModelerHandle, DesignerSelection } from './ModelerPane';
import {
  readWfeConfig,
  serviceProperties,
  readWfeUserConfig,
  userProperties,
  SERVICE_KINDS,
  type ServiceKind,
  type WfeServiceConfig,
  type WfeUserConfig,
} from './wfeDescriptor';

const { Text, Title } = Typography;

export interface InspectorProps {
  modeler: ModelerHandle | null;
  selection: DesignerSelection | null;
  businessObject: Record<string, unknown> | null;
}

/**
 * Lightweight properties inspector (Phase 0 spike). It demonstrates reading
 * and writing the `wfe:*` extension attributes round-trip: edit a value and
 * export — the attributes survive in the BPMN XML.
 *
 * <p>The full form-driven properties panel (`bpmn-js-properties-panel` with a
 * `wfe` provider) replaces this in the Designer phase.
 */
export function Inspector({ modeler, selection, businessObject }: InspectorProps) {
  const [config, setConfig] = useState<WfeServiceConfig>({});
  const [user, setUser] = useState<WfeUserConfig>({});
  const [name, setName] = useState('');

  useEffect(() => {
    setConfig(readWfeConfig(businessObject));
    setUser(readWfeUserConfig(businessObject));
    setName((businessObject?.name as string | undefined) ?? '');
  }, [businessObject]);

  const isServiceTask = useMemo(
    () => Boolean(businessObject) && String(businessObject!.$type).includes('ServiceTask'),
    [businessObject],
  );

  const isUserTask = useMemo(
    () => Boolean(businessObject) && String(businessObject!.$type).includes('UserTask'),
    [businessObject],
  );

  if (!selection) {
    return (
      <div className="inspector">
        <Empty description="Select an element to edit its properties" image={Empty.PRESENTED_IMAGE_SIMPLE} />
      </div>
    );
  }

  const kind = (config.kind ?? 'rest') as ServiceKind;

  const commit = (patch: Partial<WfeServiceConfig>) => {
    const next = { ...config, ...patch };
    setConfig(next);
    modeler?.applyProperties(selection.id, serviceProperties(next));
  };

  const commitName = (value: string) => {
    setName(value);
    modeler?.applyProperties(selection.id, { name: value });
  };

  const commitUser = (patch: Partial<WfeUserConfig>) => {
    const next = { ...user, ...patch };
    setUser(next);
    modeler?.applyProperties(selection.id, userProperties(next));
  };

  return (
    <div className="inspector">
      <div className="inspector__header">
        <Title level={5} className="inspector__title">
          Properties
        </Title>
        <Tag
          color={isServiceTask ? 'blue' : isUserTask ? 'green' : 'default'}
        >
          {selection.type}
        </Tag>
      </div>

      <Form layout="vertical" size="small">
        <Form.Item label="Name">
          <Input value={name} onChange={(e) => commitName(e.target.value)} />
        </Form.Item>

        {isUserTask && (
          <>
            <Form.Item label="Assignee">
              <Input
                value={user.assignee ?? ''}
                placeholder="username"
                onChange={(e) => commitUser({ assignee: e.target.value })}
              />
            </Form.Item>

            <Form.Item label="Candidate groups">
              <Input
                value={user.candidateGroups ?? ''}
                placeholder="approvers, reviewers"
                onChange={(e) => commitUser({ candidateGroups: e.target.value })}
              />
            </Form.Item>

            <Form.Item label="Form key">
              <Input
                value={user.formKey ?? ''}
                onChange={(e) => commitUser({ formKey: e.target.value })}
              />
            </Form.Item>
          </>
        )}

        {isServiceTask && (
          <>
            <Form.Item label="Service kind">
              <Select<ServiceKind>
                value={kind}
                options={SERVICE_KINDS.map((k) => ({ label: k.toUpperCase(), value: k }))}
                onChange={(value) => commit({ kind: value })}
              />
            </Form.Item>

            <Form.Item label="Connection">
              <Input
                value={config.connection ?? ''}
                placeholder="default"
                onChange={(e) => commit({ connection: e.target.value })}
              />
            </Form.Item>

            <Form.Item label={kind === 'soap' ? 'Operation' : 'Endpoint / resource'}>
              <Input
                value={config.operation ?? ''}
                onChange={(e) => commit({ operation: e.target.value })}
              />
            </Form.Item>

            {kind === 'rest' && (
              <Form.Item label="HTTP method">
                <Select
                  value={config.httpMethod ?? 'GET'}
                  options={['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].map((m) => ({ label: m, value: m }))}
                  onChange={(value) => commit({ httpMethod: value })}
                />
              </Form.Item>
            )}

            <Form.Item label="Timeout (ms)">
              <InputNumber
                min={100}
                max={300_000}
                style={{ width: '100%' }}
                value={config.timeoutMs}
                onChange={(value) => commit({ timeoutMs: value ?? undefined })}
              />
            </Form.Item>

            <Form.Item label="Retries">
              <InputNumber
                min={0}
                max={10}
                style={{ width: '100%' }}
                value={config.retries}
                onChange={(value) => commit({ retries: value ?? undefined })}
              />
            </Form.Item>

            <Form.Item label="Request template (JSON)">
              <Input.TextArea
                rows={4}
                value={config.requestTemplate ?? ''}
                placeholder='{ "venue": {{venue}}, "audience": {{audience}} }'
                onChange={(e) => commit({ requestTemplate: e.target.value })}
              />
            </Form.Item>
          </>
        )}

        {!isServiceTask && !isUserTask && selection.type !== 'bpmn:Process' && (
          <Text type="secondary" className="inspector__hint">
            Only service tasks and user tasks carry WFE-specific attributes in this phase.
          </Text>
        )}

        {(isServiceTask || isUserTask) && (
          <Button
            block
            danger
            icon={<DeleteOutlined />}
            onClick={() => {
              if (isUserTask) {
                modeler?.applyProperties(selection.id, {
                  'wfe:assignee': undefined,
                  'wfe:candidateGroups': undefined,
                  'wfe:formKey': undefined,
                });
                setUser({});
                return;
              }
              modeler?.applyProperties(selection.id, {
                'wfe:kind': undefined,
                'wfe:connection': undefined,
                'wfe:operation': undefined,
                'wfe:httpMethod': undefined,
                'wfe:timeoutMs': undefined,
                'wfe:retries': undefined,
                'wfe:config': undefined,
              });
              setConfig({});
            }}
          >
            Reset WFE attributes
          </Button>
        )}
      </Form>
    </div>
  );
}