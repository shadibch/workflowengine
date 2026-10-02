import { Card, Col, Empty, Row, Statistic, Typography } from 'antd';
import { ClockCircleOutlined, CheckCircleOutlined, CloseCircleOutlined, PlayCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

const { Title, Text } = Typography;

/**
 * Dashboards (live instance counters, throughput, service-call failures).
 * Metrics endpoints arrive in Phase 5; the tiles are placeholders wired to
 * zero until then.
 */
export function DashboardPage() {
  const { t } = useTranslation();

  return (
    <div>
      <Title level={3} className="page-title">
        {t('app.navigation.dashboard')}
      </Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} md={6}>
          <Card bordered>
            <Statistic title="Running" value={0} prefix={<PlayCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card bordered>
            <Statistic title="Completed" value={0} prefix={<CheckCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card bordered>
            <Statistic title="Failed" value={0} prefix={<CloseCircleOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} md={6}>
          <Card bordered>
            <Statistic title="On hold" value={0} prefix={<ClockCircleOutlined />} />
          </Card>
        </Col>
      </Row>

      <Card className="placeholder-card" bordered style={{ marginTop: 16 }}>
        <Empty description="Instance analytics will render here from Phase 5." image={Empty.PRESENTED_IMAGE_SIMPLE} />
        <Text type="secondary">Live counters stream via SSE; historical charts arrive with reports.</Text>
      </Card>
    </div>
  );
}