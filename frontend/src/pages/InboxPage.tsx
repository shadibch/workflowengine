import { Card, Empty, Typography } from 'antd';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;

/**
 * Task inbox. The engine's user tasks are persisted in the `task` table
 * (Phase 1) and surfaced here with SSE-driven updates (Phase 5). Phase 0
 * renders the empty state.
 */
export function InboxPage() {
  const { t } = useTranslation();

  return (
    <div>
      <Title level={3} className="page-title">
        {t('app.navigation.inbox')}
      </Title>
      <Card className="placeholder-card" bordered>
        <Empty description="Your task inbox is empty." image={Empty.PRESENTED_IMAGE_SIMPLE} />
        <Text type="secondary">
          Engine user tasks appear here with live updates once the engine core
          is running (Phase 3–5).
        </Text>
      </Card>
    </div>
  );
}