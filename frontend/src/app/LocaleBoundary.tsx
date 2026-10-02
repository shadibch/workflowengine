import { useEffect, type ReactNode } from 'react';
import { ConfigProvider, App as AntApp, theme as antdTheme } from 'antd';
import enUS from 'antd/locale/en_US';
import arEG from 'antd/locale/ar_EG';
import { useTranslation } from 'react-i18next';

/**
 * Renders the Ant Design config for the active locale. Switching language
 * flips the document direction so the whole UI (including bpmn-js panels)
 * mirrors correctly for Arabic.
 */
export function LocaleBoundary({ children }: { children: ReactNode }) {
  const { i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? i18n.language;
  const rtl = locale === 'ar';

  useEffect(() => {
    document.documentElement.dir = rtl ? 'rtl' : 'ltr';
    document.documentElement.lang = locale;
  }, [rtl, locale]);

  return (
    <ConfigProvider
      locale={rtl ? arEG : enUS}
      direction={rtl ? 'rtl' : 'ltr'}
      theme={{
        algorithm: antdTheme.defaultAlgorithm,
        token: {
          colorPrimary: '#1668dc',
          borderRadius: 6,
          fontFamily: "'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif",
        },
      }}
    >
      <AntApp>{children}</AntApp>
    </ConfigProvider>
  );
}