import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider, carbonTheme } from '@agentscope-ai/design';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App';
import './index.css';

dayjs.locale('zh-cn');

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* Spark Design 范式：ConfigProvider + Carbon 黑色主题。
        必须用 design 包导出的完整主题对象（含 cssVar: true）：
        全局样式里 .ant-btn-primary 的白字依赖 CSS 变量 --ant-color-text-on-primary，
        不启用 cssVar 时变量为空，黑底按钮会回退成黑字。 */}
    <ConfigProvider
      locale={zhCN}
      theme={carbonTheme.theme}
    >
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>,
);
