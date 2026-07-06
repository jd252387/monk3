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
      // Dev-only convenience for the mapping editor: serve a local git repo
      // same-origin (no CORS setup) via e.g. `npx git-http-server -p 8174 <dir>`
      // and set VITE_MAPPINGS_GIT_URL=/git/<repo> in .env.local.
      '/git': {
        target: 'http://localhost:8174',
        rewrite: (path) => path.replace(/^\/git/, ''),
      },
      // Dev-only: proxy github.com same-origin so the browser git client
      // (isomorphic-git) isn't blocked by CORS. Set
      // VITE_MAPPINGS_GIT_URL=/gh/<owner>/<repo> in .env.local. (Prefix is /gh,
      // not /github, so it doesn't collide with the /git rule above.)
      // NOTE: open the app from THIS dev server (http://localhost:5173), not from
      // Quinoa on :8080 — Quinoa forwards GETs here but drops the git-upload-pack
      // POST, so the clone 404s when loaded via :8080.
      '/gh': {
        target: 'https://github.com',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/gh/, ''),
      },
    },
  },
});
