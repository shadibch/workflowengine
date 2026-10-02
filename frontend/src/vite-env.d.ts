/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Keycloak base URL, e.g. http://localhost:8081 */
  readonly VITE_KEYCLOAK_URL: string;
  /** Keycloak realm, e.g. wfe */
  readonly VITE_KEYCLOAK_REALM: string;
  /** OIDC client id configured in the realm, e.g. wfe-frontend */
  readonly VITE_KEYCLOAK_CLIENT_ID: string;
  /** Backend base, used by the dev server proxy only. */
  readonly VITE_API_TARGET?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}