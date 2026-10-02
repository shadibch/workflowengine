import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA talks only to same-origin `/api/*`, and Vite proxies it to the
// backend. This keeps the browser free of CORS and the API URL out of the
// bundle; the dev-time proxy also forwards SSE (`/api/stream`) correctly.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8082',
        changeOrigin: true,
      },
    },
  },
  build: {
    // bpmn-js is pre-bundled; chunk-splitting game here only costs us cycles.
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          bpmn: ['bpmn-js'],
          antd: ['antd', '@ant-design/icons'],
        },
      },
    },
  },
});