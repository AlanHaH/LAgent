import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5300,
    proxy: { '/api': { target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true } },
  },
  build: {
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('echarts') || id.includes('zrender') || id.includes('vue-echarts')) return 'charts'
          if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) return 'vue-core'
          if (id.includes('markdown-it') || id.includes('dompurify')) return 'content-tools'
          return 'vendor'
        },
      },
    },
  },
})
