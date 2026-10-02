import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { useAuth } from '../auth/AuthProvider';
import {
  asConflict,
  createDefinition,
  listCategories,
  listDefinitions,
  retireDefinition,
  updateDefinition,
  type Definition,
  type DefinitionStatus,
} from '../api/definitions';

const { Title, Text } = Typography;

/**
 * Statuses the list can filter on.
 *
 * <p>ARCHIVED is deliberately absent: archiving sets the definition's soft-delete
 * marker, and search excludes those rows before the status filter is applied, so
 * offering it would produce a filter that can never match anything.
 */
const SEARCHABLE_STATUSES: DefinitionStatus[] = ['DRAFT', 'ACTIVE', 'SUSPENDED'];

/**
 * Definition catalog: search, create, open and archive.
 *
 * <p>Every action is gated on a permission, not a role, and the gating is only
 * convenience — the server re-checks each one. Hiding "New" from a viewer while
 * letting them try the API anyway would be theatre.
 */
export function DefinitionsPage() {
  const { t } = useTranslation();
  const { hasPermission } = useAuth();
  const { message } = AntApp.useApp();
  const navigate = useNavigate();

  const [definitions, setDefinitions] = useState<Definition[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | undefined>();
  const [status, setStatus] = useState<DefinitionStatus[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<Definition | null>(null);

  const canWrite = hasPermission('definition:write');
  const canDelete = hasPermission('definition:delete');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listDefinitions({ q: search, category, status, page, size });
      setDefinitions(result.content);
      setTotal(result.totalElements);
    } catch {
      // The message the user can act on comes from the caller's handler; this
      // catch only keeps a failed refresh from leaving a spinner running.
    } finally {
      setLoading(false);
    }
  }, [category, page, search, size, status]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void listCategories()
      .then(setCategories)
      .catch(() => setCategories([]));
  }, []);

  const archive = useCallback(
    async (row: Definition) => {
      try {
        await retireDefinition(row.key);
        await load();
        void message.success(t('definitions.archive.done'));
      } catch (error) {
        void message.error(error instanceof Error ? error.message : t('common.error'));
      }
    },
    [load, message, t],
  );

  const columns = useMemo(
    () => [
      {
        title: t('definitions.columns.name'),
        dataIndex: 'name',
        key: 'name',
        render: (_: unknown, row: Definition) => (
          <div>
            <Text strong>{row.name}</Text>
            <div>
              <Text type="secondary" code>
                {row.key}
              </Text>
            </div>
          </div>
        ),
      },
      {
        title: t('definitions.columns.category'),
        dataIndex: 'category',
        key: 'category',
        render: (value: string | null) => value ?? <Text type="secondary">—</Text>,
      },
      {
        title: t('definitions.columns.status'),
        dataIndex: 'status',
        key: 'status',
        render: (value: DefinitionStatus) => <StatusTag status={value} />,
      },
      {
        title: t('definitions.columns.published'),
        dataIndex: 'latestPublishedVersion',
        key: 'latestPublishedVersion',
        render: (value: number | null) =>
          value === null ? <Text type="secondary">—</Text> : `v${value}`,
      },
      {
        title: t('definitions.columns.updated'),
        dataIndex: 'updatedAt',
        key: 'updatedAt',
        render: (value: string) => new Date(value).toLocaleString(),
      },
      {
        title: '',
        key: 'actions',
        render: (_: unknown, row: Definition) => (
          <Space>
            <Button type="link" size="small" onClick={() => navigate(`/definitions/${row.key}`)}>
              {t('definitions.actions.open')}
            </Button>
            {canWrite && row.status !== 'ARCHIVED' && (
              <Button type="link" size="small" icon={<EditOutlined />} onClick={() => setEditing(row)}>
                {t('definitions.actions.edit')}
              </Button>
            )}
            {canDelete && row.status !== 'ARCHIVED' && (
              <Popconfirm
                title={t('definitions.archive.confirm')}
                description={t('definitions.archive.hint')}
                okText={t('definitions.archive.confirm')}
                cancelText={t('common.cancel')}
                onConfirm={() => void archive(row)}
              >
                <Button type="link" size="small" danger>
                  {t('definitions.actions.archive')}
                </Button>
              </Popconfirm>
            )}
          </Space>
        ),
      },
    ],
    [archive, canDelete, canWrite, navigate, t],
  );

  async function submitEdit(values: {
    name: string;
    category?: string;
    description?: string;
    status?: DefinitionStatus;
  }) {
    if (!editing) return;
    try {
      await updateDefinition(editing.key, {
        name: values.name,
        // Empty means clear, not "leave alone" — that is what the API documents.
        category: values.category || null,
        description: values.description || null,
        status: values.status,
      });
      setEditing(null);
      await load();
      void message.success(t('definitions.edit.done'));
    } catch (error) {
      void message.error(error instanceof Error ? error.message : t('common.error'));
    }
  }

  async function submitCreate(values: { key: string; name: string; category?: string; description?: string }) {
    try {
      const created = await createDefinition({
        key: values.key,
        name: values.name,
        category: values.category || null,
        description: values.description || null,
      });
      setCreateOpen(false);
      navigate(`/definitions/${created.key}`);
    } catch (error) {
      const conflict = asConflict(error);
      if (conflict?.kind === 'definition-key-taken') {
        // The server suggests a free key; offering it beats making the user guess.
        void message.error(`${error instanceof Error ? error.message : conflict.key} → ${conflict.suggestion}`);
        return;
      }
      void message.error(error instanceof Error ? error.message : t('common.error'));
    }
  }

  return (
    <div>
      <Title level={3} className="page-title">
        {t('app.navigation.definitions')}
      </Title>

      <Card bordered={false}>
        <Space style={{ marginBottom: 16 }} wrap>
          <Input
            allowClear
            prefix={<SearchOutlined />}
            placeholder={t('definitions.search.placeholder')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onPressEnter={() => setSearch(query)}
            style={{ width: 260 }}
          />
          <Select
            allowClear
            mode="multiple"
            style={{ minWidth: 220 }}
            placeholder={t('definitions.filter.status')}
            value={status}
            onChange={(next: DefinitionStatus[]) => {
              setStatus(next);
              setPage(0);
            }}
            options={SEARCHABLE_STATUSES.map((value) => ({
              value,
              label: t(`definitions.status.${value.toLowerCase()}`),
            }))}
          />
          {categories.length > 0 && (
            <Select
              allowClear
              style={{ minWidth: 160 }}
              placeholder={t('definitions.filter.category')}
              value={category}
              onChange={(value: string | undefined) => {
                setCategory(value);
                setPage(0);
              }}
              options={categories.map((value) => ({ value, label: value }))}
            />
          )}
          {canWrite && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
              {t('definitions.actions.create')}
            </Button>
          )}
        </Space>

        <Table<Definition>
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={definitions}
          pagination={{
            current: page + 1,
            pageSize: size,
            total,
            showSizeChanger: true,
            pageSizeOptions: [10, 20, 50],
            onChange: (nextPage, nextSize) => {
              setPage(nextPage - 1);
              setSize(nextSize);
            },
          }}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={canWrite ? t('definitions.empty.designer') : t('definitions.empty.viewer')}
              />
            ),
          }}
        />
      </Card>

      <CreateDefinitionModal open={createOpen} onClose={() => setCreateOpen(false)} onSubmit={submitCreate} />
      <EditDefinitionDrawer definition={editing} onClose={() => setEditing(null)} onSubmit={submitEdit} />
    </div>
  );
}

export function StatusTag({ status }: { status: DefinitionStatus }) {
  const { t } = useTranslation();
  const colour =
    status === 'ACTIVE' ? 'green' : status === 'DRAFT' ? 'blue' : status === 'SUSPENDED' ? 'orange' : 'default';
  return <Tag color={colour}>{t(`definitions.status.${status.toLowerCase()}`)}</Tag>;
}

interface CreateModalProps {
  open: boolean;
  onClose: () => void;
  onSubmit: (values: { key: string; name: string; category?: string; description?: string }) => Promise<void>;
}

/**
 * Create dialog.
 *
 * <p>The key is fixed at creation and never reused, which is why it is typed by
 * hand with an explicit pattern rather than generated: a key that appears in URLs,
 * audit records and start requests should be chosen, not allocated.
 */
function CreateDefinitionModal({ open, onClose, onSubmit }: CreateModalProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (open) {
      form.resetFields();
    }
  }, [form, open]);

  return (
    <Modal
      open={open}
      title={t('definitions.create.title')}
      okText={t('definitions.create.submit')}
      cancelText={t('common.cancel')}
      confirmLoading={submitting}
      onCancel={onClose}
      onOk={async () => {
        const values = await form.validateFields();
        setSubmitting(true);
        try {
          await onSubmit(values);
        } finally {
          setSubmitting(false);
        }
      }}
      destroyOnHidden
    >
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item
          name="key"
          label={t('definitions.create.key')}
          rules={[
            { required: true, message: t('definitions.create.keyRequired') },
            {
              pattern: /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/,
              message: t('definitions.create.keyPattern'),
            },
            { max: 64, message: t('definitions.create.keyTooLong') },
          ]}
        >
          <Input placeholder="invoice-approval" autoComplete="off" />
        </Form.Item>
        <Form.Item name="name" label={t('definitions.create.name')} rules={[{ required: true }]}>
          <Input placeholder={t('definitions.create.namePlaceholder')} />
        </Form.Item>
        <Form.Item name="category" label={t('definitions.create.category')}>
          <Input placeholder="Finance" />
        </Form.Item>
        <Form.Item name="description" label={t('definitions.create.description')}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>
    </Modal>
  );
}

/** Statuses a definition can be moved to from its current one. Mirrors the server rule. */
function allowedTransitions(from: DefinitionStatus): DefinitionStatus[] {
  switch (from) {
    case 'DRAFT':
      return ['ACTIVE'];
    case 'ACTIVE':
      return ['SUSPENDED'];
    case 'SUSPENDED':
      return ['ACTIVE'];
    case 'ARCHIVED':
      return [];
  }
}

interface EditDrawerProps {
  definition: Definition | null;
  onClose: () => void;
  onSubmit: (values: {
    name: string;
    category?: string;
    description?: string;
    status?: DefinitionStatus;
  }) => Promise<void>;
}

/**
 * Metadata and lifecycle drawer.
 *
 * <p>Only transitions the server permits are offered: ACTIVE requires a published
 * version, and there is no way back to DRAFT. Offering an action that the API
 * will reject is worse than not offering it.
 */
function EditDefinitionDrawer({ definition, onClose, onSubmit }: EditDrawerProps) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (definition) {
      form.setFieldsValue({
        name: definition.name,
        category: definition.category ?? undefined,
        description: definition.description ?? undefined,
      });
    }
  }, [definition, form]);

  if (!definition) {
    return null;
  }

  const transitions = allowedTransitions(definition.status);

  return (
    <Drawer
      open
      title={t('definitions.edit.title')}
      width={480}
      onClose={onClose}
      extra={
        <Space>
          <Button onClick={onClose}>{t('common.cancel')}</Button>
          <Button
            type="primary"
            loading={submitting}
            onClick={async () => {
              const values = await form.validateFields();
              setSubmitting(true);
              try {
                await onSubmit(values);
              } finally {
                setSubmitting(false);
              }
            }}
          >
            {t('definitions.edit.submit')}
          </Button>
        </Space>
      }
    >
      <Descriptions size="small" column={1} style={{ marginBottom: 16 }}>
        <Descriptions.Item label={t('definitions.edit.key')}>
          <Text code>{definition.key}</Text>
        </Descriptions.Item>
        <Descriptions.Item label={t('definitions.columns.status')}>
          <StatusTag status={definition.status} />
        </Descriptions.Item>
        <Descriptions.Item label={t('definitions.edit.draftRevision')}>{definition.draftRevision}</Descriptions.Item>
      </Descriptions>

      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item name="name" label={t('definitions.create.name')} rules={[{ required: true }]}>
          <Input />
        </Form.Item>
        <Form.Item name="category" label={t('definitions.create.category')}>
          <Input />
        </Form.Item>
        <Form.Item name="description" label={t('definitions.create.description')}>
          <Input.TextArea rows={3} />
        </Form.Item>
      </Form>

      {transitions.length > 0 && (
        <Form.Item label={t('definitions.edit.status')}>
          <Select
            allowClear
            style={{ width: 200 }}
            placeholder={t('definitions.edit.statusPlaceholder')}
            options={transitions.map((value) => ({
              value,
              label: t(`definitions.status.${value.toLowerCase()}`),
            }))}
            onChange={(value: DefinitionStatus | undefined) => {
              if (!value) return;
              void onSubmit({ ...form.getFieldsValue(), status: value });
            }}
          />
        </Form.Item>
      )}
    </Drawer>
  );
}
