import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  // Read only PORT from root environment. Secrets are never assigned to Vite's public define/env surface.
  const environment = loadEnv(mode, '../..', ['PORT', 'API_PORT']);
  const port = process.env.API_PORT || process.env.PORT || environment.API_PORT || environment.PORT || '8787';
  const target = `http://127.0.0.1:${/^\d{2,5}$/.test(port) ? port : '8787'}`;
  return {
    plugins: [react()],
    server: { proxy: { '/api': target, '/downloads': target } },
  };
});
