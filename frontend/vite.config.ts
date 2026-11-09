import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5174,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            const type = String(proxyRes.headers['content-type'] || '')
            if (!type.includes('text/event-stream')) return
            proxyRes.headers['cache-control'] = 'no-cache, no-transform'
            proxyRes.headers['x-accel-buffering'] = 'no'
          })
          proxy.on('error', (error, req, res) => {
            if (!res || !('writeHead' in res) || res.headersSent) return
            const url = req.url ?? ''
            const accept = String(req.headers.accept ?? '')
            if (accept.includes('text/html') || url.startsWith('/api/auth/')) {
              res.writeHead(302, { Location: '/login?auth_error=offline' })
              res.end()
              return
            }
            res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' })
            res.end('本地接口没有起来。')
          })
        },
      },
    },
  },
})
