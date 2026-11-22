// 導頁後在目的頁顯示一則短暫提示（例：刪除旅程後回列表的「已刪除『名稱』」）；約 6 秒自動消失，可手動關閉
import { useSyncExternalStore } from 'react'

export const FLASH_MS = 6000

let current: { id: number; text: string } | null = null
let timer: number | null = null
let seq = 0
const listeners = new Set<() => void>()
const emit = () => listeners.forEach((l) => l())

export function dismissFlash() {
  if (timer !== null) window.clearTimeout(timer)
  timer = null
  current = null
  emit()
}

export function showFlash(text: string) {
  if (timer !== null) window.clearTimeout(timer)
  current = { id: ++seq, text }
  timer = window.setTimeout(dismissFlash, FLASH_MS)
  emit()
}

function subscribe(cb: () => void) {
  listeners.add(cb)
  return () => {
    listeners.delete(cb)
  }
}

export function FlashBar() {
  const flash = useSyncExternalStore(subscribe, () => current)
  if (!flash) return null
  return (
    <div className="msg success flash" role="status" key={flash.id}>
      <p>{flash.text}</p>
      <button type="button" className="btn ghost-dark sm" aria-label="關閉提示" onClick={dismissFlash}>
        關閉
      </button>
    </div>
  )
}
