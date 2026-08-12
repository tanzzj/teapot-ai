/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  // antd / Spark Design 组件自带样式，Tailwind 只做布局微调，关闭 preflight 避免冲突
  corePlugins: { preflight: false },
  theme: { extend: {} },
  plugins: [],
};
