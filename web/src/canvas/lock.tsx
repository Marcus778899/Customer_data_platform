// 送審中草稿鎖定：文字欄用原生 readOnly（仍可選取、朗讀），選單、核取、按鈕用 disabled。
import { createContext, useContext } from 'react'

const LockContext = createContext(false)
export const LockProvider = LockContext.Provider
export const useLocked = () => useContext(LockContext)
