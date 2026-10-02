import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntApp } from 'antd';

import './styles.css';
import './i18n';
import { AppRoot } from './App';
import { AuthProvider } from './auth/AuthProvider';
import { LocaleBoundary } from './app/LocaleBoundary';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 15_000,
      refetchOnWindowFocus: false,
    },
  },
});

// Surface unexpected rejections instead of dying silently.
window.addEventListener('unhandledrejection', (event) => {
  console.warn('[unhandled rejection]', event.reason);
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <LocaleBoundary>
            {/* AntApp supplies the context that `App.useApp()` hooks read. Without
                it every message/notification call falls back to the static API,
                which ignores the ConfigProvider theme and locale. */}
            <AntApp>
              <AppRoot />
            </AntApp>
          </LocaleBoundary>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);