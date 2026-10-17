import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { installMockApi } from './mock/mockApi'
import './styles/tokens.css'
import './styles/app.css'

// 後端未完成時用 mock（環境變數 VITE_API_MOCK=true）；INT-01 接真實 API 後移除
if (import.meta.env.VITE_API_MOCK === 'true') installMockApi()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
