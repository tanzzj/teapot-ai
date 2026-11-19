import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// @agentscope-ai/chat 1.1.71 的 thinking/tool 过程卡在 loading 期间自动展开；
// 需求是过程块默认折叠。spark-design 源码仓已同步修改，待下次发版携带；
// 在此之前构建期对编译产物打补丁（命中数≠1 即抛错，包升级后不会静默失效）。
function collapseProcessBlocks() {
  const patches: [string, string, string][] = [
    ['Response/Tool.js', 'var defaultOpen = loading;', 'var defaultOpen = false;'],
    ['Response/Reasoning.js', 'title: "Thinking",', 'title: "Thinking",\n    defaultOpen: false,'],
  ];
  return {
    name: 'collapse-process-blocks',
    enforce: 'pre' as const,
    transform(code: string, id: string) {
      for (const [suffix, from, to] of patches) {
        if (!id.includes('@agentscope-ai/chat') || !id.endsWith(suffix)) continue;
        const hits = code.split(from).length - 1;
        if (hits !== 1) {
          throw new Error(`collapse-process-blocks: ${suffix} expected 1 hit, got ${hits}`);
        }
        return code.replace(from, to);
      }
      return null;
    },
  };
}

// SPEC §12.4：dev server 5173，/api 与 /agui 代理到后端 9126
export default defineConfig({
  plugins: [collapseProcessBlocks(), react()],
  // dev 预打包会绕过 transform 补丁，排除后 dev 与 build 行为一致
  optimizeDeps: { exclude: ['@agentscope-ai/chat'] },
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
