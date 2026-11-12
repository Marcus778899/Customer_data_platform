// 設定只從環境變數讀（make web-dev 由主目錄 .env 匯出；變數清單見 .env.example）
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.WEB_PORT),
    strictPort: true,
    // 後端 API 一律掛在 /api 底下（路徑細節見 docs/contracts/）
    proxy: { '/api': `http://localhost:${process.env.API_PORT}` },
  },
  test: {
    // tests/acceptance 由 qa 撰寫、worker 不可改；單元測試放 tests/unit 或與原始碼同目錄
    include: ['src/**/*.test.{ts,tsx}', 'tests/**/*.test.{ts,tsx}'],
    // make coverage 加 --coverage 才啟用；Cobertura XML 給 CI 的改動覆蓋率檢查（docs/design/INIT-01.md「覆蓋率」）
    // include 讓沒被任何測試 import 的檔也列進報告，新檔不寫測試也量得到
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{ts,tsx}'],
      exclude: ['src/**/*.test.{ts,tsx}'],
      reporter: ['cobertura', 'text-summary'],
      reportsDirectory: 'coverage',
    },
  },
})
