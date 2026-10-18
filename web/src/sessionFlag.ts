// 只記「曾經登入」旗標（不存帳號、密碼或 session 值），用來在重新載入後辨識 session 逾時
const KEY = 'cdp.wasLoggedIn'

export function markLoggedIn() {
  try {
    window.localStorage.setItem(KEY, '1')
  } catch {
    // 儲存不可用時退回僅在記憶體內判斷
  }
}

/** 讀取並清除旗標；回傳先前是否有旗標 */
export function consumeLoggedIn(): boolean {
  try {
    const had = window.localStorage.getItem(KEY) === '1'
    window.localStorage.removeItem(KEY)
    return had
  } catch {
    return false
  }
}
