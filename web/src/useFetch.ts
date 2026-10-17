import { useCallback, useEffect, useState } from 'react'
import { ApiError } from './api/client'

interface Settled<T> {
  load: () => Promise<T>
  nonce: number
  data?: T
  error?: ApiError
}

/**
 * 以 `load` 的身分（用 useCallback 固定）決定何時重打；`load` 為 null 代表閒置。
 * data／error 只在對應「目前的請求」時有值；last 是最近一次成功的資料（重新整理時保留畫面用）。
 */
export function useFetch<T>(load: (() => Promise<T>) | null) {
  const [nonce, setNonce] = useState(0)
  const [state, setState] = useState<Settled<T> | null>(null)
  const [last, setLast] = useState<T | undefined>(undefined)

  useEffect(() => {
    if (!load) return
    let cancelled = false
    load().then(
      (data) => {
        if (cancelled) return
        setState({ load, nonce, data })
        setLast(data)
      },
      (e: unknown) => {
        if (cancelled) return
        setState({ load, nonce, error: e instanceof ApiError ? e : new ApiError(0, null, null) })
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, nonce])

  const current = load !== null && state !== null && state.load === load && state.nonce === nonce ? state : null
  const reload = useCallback(() => setNonce((n) => n + 1), [])
  return {
    data: current?.data,
    error: current?.error,
    loading: load !== null && current === null,
    last,
    reload,
  }
}
