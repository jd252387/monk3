import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The SPA is served at the root path by Quarkus/Quinoa, so the default base ('/')
// is correct. The dev proxy below only matters when running bare `vite` (not via
// quarkusDev, where Quinoa fronts this dev server and the API is same-origin).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
  },
  server: {
    // Vite 5 binds the IPv6 loopback (::1) by default, but Quinoa's dev-server
    // proxy dials the IPv4 loopback (127.0.0.1). That mismatch makes every
    // proxied request hang. Pin Vite to IPv4 (and a fixed port) so Quinoa can
    // reach it.
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/queries': 'http://localhost:8080',
    },
  },
});
