import { useCallback, useMemo, useState } from 'react'
import { ApiError } from './api/client'
import type { Page } from './api/types'
import { useFetch } from './useFetch'

/** 以 cursor 分頁的清單：`load` 用 useCallback／useMemo 固定；null = 閒置 */
export function usePagedList<T>(load: ((cursor?: string) => Promise<Page<T>>) | null) {
  const first = useMemo(() => (load ? () => load() : null), [load])
  const { data, error, loading, reload } = useFetch(first)
  const [more, setMore] = useState<{ base: Page<T>; items: T[]; next: string | null } | null>(null)
  const [moreBusy, setMoreBusy] = useState(false)
  const [moreErr, setMoreErr] = useState<{ base: Page<T>; error: ApiError } | null>(null)

  const mine = data && more && more.base === data ? more : null
  const items = data ? [...data.items, ...(mine?.items ?? [])] : []
  const next = data ? (mine ? mine.next : data.next_cursor) : null
  const moreError = data && moreErr && moreErr.base === data ? moreErr.error : null

  const loadMore = useCallback(async () => {
    if (!load || !data || !next) return
    setMoreBusy(true)
    setMoreErr(null)
    try {
      const page = await load(next)
      setMore({ base: data, items: [...(mine?.items ?? []), ...page.items], next: page.next_cursor })
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) return
      setMoreErr({ base: data, error: e instanceof ApiError ? e : new ApiError(0, null, null) })
    } finally {
      setMoreBusy(false)
    }
  }, [load, data, next, mine])

  return { data, items, next, error, loading, reload, loadMore, moreBusy, moreError }
}
