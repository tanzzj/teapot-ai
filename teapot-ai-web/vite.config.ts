import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// SPEC §12.4：dev server 5173，/api 与 /agui 代理到后端 9126
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:9126', changeOrigin: true },
      '/agui': { target: 'http://localhost:9126', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 2000,
  },
});
