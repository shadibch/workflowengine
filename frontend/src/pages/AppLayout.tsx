import { useMemo } from 'react';
import { Layout, Menu, Dropdown, Avatar, Space, Select, Typography } from 'antd';
import {
  DashboardOutlined,
  FileTextOutlined,
  InboxOutlined,
  PartitionOutlined,
  SettingOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

import { useAuth } from '../auth/AuthProvider';
import { setLocale, supportedLanguages } from '../i18n';

const { Header, Sider, Content } = Layout;
const { Title, Text } = Typography;

const NAV_ITEMS = [
  { key: '/', icon: <DashboardOutlined />, labelKey: 'app.navigation.dashboard' },
  { key: '/definitions', icon: <PartitionOutlined />, labelKey: 'app.navigation.definitions' },
  { key: '/inbox', icon: <InboxOutlined />, labelKey: 'app.navigation.inbox' },
  { key: '/instances', icon: <FileTextOutlined />, labelKey: 'app.navigation.instances' },
  { key: '/settings', icon: <SettingOutlined />, labelKey: 'app.navigation.settings' },
];

export function AppLayout() {
  const { t, i18n } = useTranslation();
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const selectedKey = useMemo(
    () => NAV_ITEMS.map((item) => item.key).find((key) => location.pathname.startsWith(key)) ?? '/',
    [location.pathname],
  );

  const userMenu = {
    items: [
      { key: 'logout', icon: <LogoutOutlined />, label: t('auth.signOut', 'Sign out') },
    ],
    onClick: (info: { key: string }) => {
      if (info.key === 'logout') void logout();
    },
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth={64} theme="light" style={{ borderInlineEnd: '1px solid #eee' }}>
        <div className="app-logo">
          <div className="app-logo__mark" aria-hidden />
          <Title level={5} className="app-logo__text">
            {t('app.name')}
          </Title>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          items={NAV_ITEMS.map((item) => ({ ...item, label: t(item.labelKey) }))}
          onClick={(info) => navigate(info.key)}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            borderBottom: '1px solid #eee',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            paddingInline: 24,
          }}
        >
          <Space>
            <Select
              size="small"
              variant="borderless"
              value={i18n.language}
              onChange={(code) => setLocale(code as typeof supportedLanguages[number]['code'])}
              options={supportedLanguages.map((l) => ({ value: l.code, label: l.label }))}
              style={{ width: 96 }}
            />
          </Space>
          <Dropdown menu={userMenu} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} />
              <Text strong>{user?.username ?? '—'}</Text>
              <Text type="secondary">{user?.tenantId}</Text>
            </Space>
          </Dropdown>
        </Header>

        <Content style={{ padding: 24, overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}