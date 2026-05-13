import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 9101,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:9100',
        changeOrigin: true
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('element-plus') || id.includes('@element-plus')) {
              return 'vendor-element-plus'
            }
            if (id.includes('vue')) {
              return 'vendor-vue'
            }
            return 'vendor'
          }
        }
      }
    }
  },
  base: ''
})
