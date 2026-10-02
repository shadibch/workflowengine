import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { AppLayout } from './pages/AppLayout';
import { DefinitionsPage } from './pages/DefinitionsPage';
import { DesignerPage } from './pages/DesignerPage';
import { InboxPage } from './pages/InboxPage';
import { DashboardPage } from './pages/DashboardPage';
import { SettingsPage } from './pages/SettingsPage';

/**
 * Application shell.
 *
 * <p>Routing is flat and role-aware at the nav level. The server remains the
 * enforcement point; hiding a menu item is convenience, not control.
 */
export function AppRoot() {
  const { isAuthenticated, isInitializing } = useAuth();

  if (isInitializing) {
    return <SplashScreen />;
  }

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  return (
    <Routes>
      <Route path="/login" element={<Navigate to="/" replace />} />
      <Route element={<AppLayout />}>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/definitions" element={<DefinitionsPage />} />
        {/* Before the dynamic segment: 'new' is a legal key pattern, so ordering is
            the only thing keeping it from being read as a definition key. */}
        <Route path="/definitions/new" element={<Navigate to="/definitions" replace />} />
        <Route path="/definitions/:definitionId" element={<DesignerPage />} />
        <Route path="/inbox" element={<InboxPage />} />
        <Route path="/instances" element={<DashboardPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

function LoginPage() {
  const { login } = useAuth();
  return (
    <div className="splash">
      <div className="splash__logo" />
      <h1>Workflow Engine</h1>
      <p>Sign in to manage process definitions and tasks.</p>
      <button type="button" className="splash__button" onClick={() => void login()}>
        Sign in with Keycloak
      </button>
    </div>
  );
}

function SplashScreen() {
  return (
    <div className="splash">
      <div className="splash__spinner" aria-label="loading" />
      <p>Signing you in…</p>
    </div>
  );
}