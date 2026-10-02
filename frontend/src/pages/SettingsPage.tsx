import { Card, Divider, List, Space, Switch, Typography } from 'antd';
import { useTranslation } from 'react-i18next';
import { useState } from 'react';

import { useAuth } from '../auth/AuthProvider';

const { Title, Text } = Typography;

/**
 * Account and tenant settings. Phase 0: language preference + the server-side
 * profile summary. Auditing/notification/role management surfaces in later
 * phases.
 */
export function SettingsPage() {
  const { user } = useAuth();
  const { t } = useTranslation();
  const [subscribe, setSubscribe] = useState(true);

  return (
    <div style={{ maxWidth: 720 }}>
      <Title level={3} className="page-title">
        {t('app.navigation.settings')}
      </Title>

      <Card title="Profile" bordered>
        <List size="small">
          <List.Item>
            <Text type="secondary">Username</Text>
            <Text strong>{user?.username ?? '—'}</Text>
          </List.Item>
          <List.Item>
            <Text type="secondary">Email</Text>
            <Text>{user?.email ?? '—'}</Text>
          </List.Item>
          <List.Item>
            <Text type="secondary">Tenant</Text>
            <Text>{user?.tenantId}</Text>
          </List.Item>
          <List.Item>
            <Text type="secondary">Roles</Text>
            <Text>{(user?.roles ?? []).join(', ') || '—'}</Text>
          </List.Item>
          <List.Item>
            <Text type="secondary">Permissions</Text>
            <Text>{(user?.permissions ?? []).join(', ') || '—'}</Text>
          </List.Item>
        </List>
      </Card>

      <Card title="Notifications" bordered style={{ marginTop: 16 }}>
        <List size="small">
          <List.Item
            actions={[
              <Space key="switch">
                <Text type="secondary">{subscribe ? 'On' : 'Off'}</Text>
                <Switch checked={subscribe} onChange={setSubscribe} />
              </Space>,
            ]}
          >
            <List.Item.Meta title="Task reminders" description="E-mail digest when a task is waiting on you." />
          </List.Item>
        </List>
      </Card>

      <Divider />
      <Text type="secondary">Role management, audit trail and tenant admin arrive in later phases.</Text>
    </div>
  );
}