import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// The dashboard talks to the backend through this proxy during development, so
// the browser only ever sees one origin and no CORS preflight is involved.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    // Bound to localhost by default. Pass --host to expose the dashboard on the
    // LAN (for checking it from a phone); do not do that on an untrusted network,
    // the API has no authentication.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})
