import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from '@agentscope-ai/design';
import bailianTheme from '@agentscope-ai/design/lib/antd/themes/bailianTheme.json';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import App from './App';
import './index.css';

dayjs.locale('zh-cn');

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    {/* Spark Design 范式：ConfigProvider + 百炼主题 token（SPEC §12）；主按钮黑底白字 */}
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: bailianTheme,
        components: {
          Button: {
            colorPrimary: '#1a1a1d',
            colorPrimaryHover: '#3e3f42',
            colorPrimaryActive: '#000000',
            primaryColor: '#ffffff',
          },
        },
      }}
    >
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ConfigProvider>
  </React.StrictMode>,
);
