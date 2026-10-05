import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ConfigProvider } from 'antd'
import ar_EG from 'antd/locale/ar_EG'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider direction="rtl" locale={ar_EG}>
      <App />
    </ConfigProvider>
  </StrictMode>,
)
