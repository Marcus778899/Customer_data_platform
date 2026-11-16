// 全域測試設定：整套 jsdom 並行負載下，預設 1000ms 的 findBy／waitFor 會偶發逾時
import { configure } from '@testing-library/react'

configure({ asyncUtilTimeout: 5000 })
