// 草稿狀態與手動儲存：只有按「儲存草稿」（或送審、驗證前）才 PUT；同時只送一個請求；revision 取自上一次回應。
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getDraft, getFieldKeys, getJourney, putDraft, submitDraft, validateDraft, withdrawReview } from '../api/client'
import type { Draft, FieldKeys, Issue, JourneyDetail, Review, UserRef, ValidationReport } from '../api/types'
import { entryEventName, fillLayout, isAutoLayout, layoutAll, topologyKey, type Def, type Layout } from './model'

export type Banner =
  | { kind: 'conflict'; by: UserRef | null; at: string | null }
  | { kind: 'inReview' }
  | { kind: 'aborted'; detail: JourneyDetail | null }
  | { kind: 'saveFailed'; requestId: string | null }
  | { kind: 'deleted' }
  | { kind: 'submitBlocked'; count: number }
  | { kind: 'submitFailed'; requestId: string | null }

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError(0, null, null))

/** 儲存失敗：給狀態字用的白話原因（內容仍保留在畫面上） */
export interface SaveError {
  reason: string
  requestId: string | null
}

export function useDraftEditor(journeyId: string) {
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [def, setDef] = useState<Def | null>(null)
  const [layout, setLayout] = useState<Layout | null>(null)
  const [meta, setMeta] = useState<{ updatedAt: string; updatedBy: UserRef | null } | null>(null)
  const [detail, setDetail] = useState<JourneyDetail | null>(null)
  const [review, setReview] = useState<Review | null>(null)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const [banner, setBanner] = useState<Banner | null>(null)
  const [report, setReport] = useState<ValidationReport | null>(null)
  const [reportOpen, setReportOpen] = useState(false)
  const [validating, setValidating] = useState(false)
  const [fieldKeys, setFieldKeys] = useState<FieldKeys | null>(null)
  const [attempt, setAttempt] = useState(0)
  const [saveError, setSaveError] = useState<SaveError | null>(null)

  const defRef = useRef<Def | null>(null)
  const layoutRef = useRef<Layout | null>(null)
  const revRef = useRef(0)
  const dirtyRef = useRef(false)
  /** 送審中：草稿鎖定，編輯與儲存都不動作（契約 §3.2） */
  const lockedRef = useRef(false)
  const reviewRef = useRef<Review | null>(null)
  const inflight = useRef<Promise<boolean> | null>(null)
  const alive = useRef(true)

  const apply = useCallback((d: Draft) => {
    defRef.current = d.definition
    layoutRef.current = d.layout
    revRef.current = d.revision
    dirtyRef.current = false
    lockedRef.current = d.latest_review?.status === 'PENDING'
    reviewRef.current = d.latest_review ?? null
    setReview(d.latest_review ?? null)
    setDef(d.definition)
    setLayout(d.layout)
    setMeta({ updatedAt: d.updated_at, updatedBy: d.updated_by })
    setDirty(false)
    setSaveError(null)
    setBanner(null)
  }, [])

  useEffect(() => {
    alive.current = true
    let cancelled = false
    getDraft(journeyId).then(
      (d) => {
        if (cancelled) return
        apply(d)
        setPhase('ready')
      },
      (e: unknown) => {
        if (cancelled) return
        setLoadError(asError(e))
        setPhase('error')
      },
    )
    getJourney(journeyId).then(
      (j) => !cancelled && setDetail(j),
      () => undefined,
    )
    return () => {
      cancelled = true
    }
  }, [journeyId, attempt, apply])

  const reload = useCallback(() => {
    setPhase('loading')
    setAttempt((n) => n + 1)
  }, [])

  // ---------- 儲存（手動；送審與驗證前自動存一次） ----------
  /** 存完回傳 true（含沒有未儲存內容）；失敗或被擋回傳 false，內容保留在畫面上 */
  const save = useCallback((): Promise<boolean> => {
    if (inflight.current) return inflight.current
    if (lockedRef.current || !defRef.current) return Promise.resolve(!dirtyRef.current)
    if (!dirtyRef.current) return Promise.resolve(true)
    const definition = defRef.current
    const full = fillLayout(definition, layoutRef.current)
    dirtyRef.current = false
    setSaving(true)
    setSaveError(null)
    const run = (async (): Promise<boolean> => {
      let ok = false
      try {
        const res = await putDraft(journeyId, { revision: revRef.current, definition, layout: full })
        revRef.current = res.revision
        ok = true
        if (alive.current) {
          setMeta({ updatedAt: res.updated_at, updatedBy: res.updated_by })
          setSavedAt(res.updated_at ?? new Date().toISOString())
          setBanner(null)
        }
      } catch (e) {
        dirtyRef.current = true
        if (!alive.current) return false
        const err = asError(e)
        if (err.status === 409 && err.code === 'DRAFT_CONFLICT') {
          setBanner({ kind: 'conflict', by: null, at: null })
          getDraft(journeyId).then(
            (d) => alive.current && setBanner({ kind: 'conflict', by: d.updated_by, at: d.updated_at }),
            () => undefined,
          )
        } else if (err.status === 409 && err.code === 'DRAFT_IN_REVIEW') {
          lockedRef.current = true
          setBanner({ kind: 'inReview' })
        } else if (err.status === 409 && err.code === 'JOURNEY_ABORTED') {
          setBanner({ kind: 'aborted', detail: null })
          getJourney(journeyId).then(
            (j) => alive.current && setBanner({ kind: 'aborted', detail: j }),
            () => undefined,
          )
        } else if (err.status === 404 && err.code === 'JOURNEY_NOT_FOUND') {
          setBanner({ kind: 'deleted' })
        } else if (err.status !== 401) {
          setSaveError({
            reason: err.status === 0 ? '網路不穩，內容還在這個畫面上' : '伺服器暫時無法儲存，內容還在這個畫面上',
            requestId: err.requestId,
          })
          setBanner({ kind: 'saveFailed', requestId: err.requestId })
        }
      } finally {
        inflight.current = null
        if (alive.current) {
          setSaving(false)
          setDirty(dirtyRef.current)
        }
      }
      return ok
    })()
    inflight.current = run
    return run
  }, [journeyId])

  /** 立刻存完（驗證、送審前）；回傳是否已無未儲存內容 */
  const flush = useCallback(async () => {
    if (inflight.current) await inflight.current
    if (dirtyRef.current) await save()
    return !dirtyRef.current
  }, [save])

  const edit = useCallback(
    (fn: (d: Def, l: Layout) => [Def, Layout], opts?: { keepLayout?: boolean }) => {
      if (!defRef.current || lockedRef.current) return
      const prevDef = defRef.current
      const prevLayout = fillLayout(prevDef, layoutRef.current)
      const [d, edited] = fn(prevDef, prevLayout)
      // 新增節點或接線改變後重新分層排版；使用者拖曳過的版面（與自動排版不同）原樣保留
      // keepLayout：拖進畫布、放開在空白處新增的節點已有指定位置，不重排
      const l = !opts?.keepLayout && topologyKey(d) !== topologyKey(prevDef) && isAutoLayout(prevDef, prevLayout) ? layoutAll(d) : edited
      defRef.current = d
      layoutRef.current = l
      dirtyRef.current = true
      setDef(d)
      setLayout(l)
      setDirty(true)
    },
    [],
  )

  useEffect(
    () => () => {
      alive.current = false
    },
    [],
  )

  const loadLatest = useCallback(async () => {
    try {
      apply(await getDraft(journeyId))
    } catch (e) {
      const err = asError(e)
      setBanner({ kind: 'saveFailed', requestId: err.requestId })
    }
  }, [journeyId, apply])

  /** 撤回送審；失敗丟出 ApiError 由對話框顯示。成功後重新載入草稿（解鎖） */
  const withdraw = useCallback(
    async (reason: string) => {
      let target = lockedRef.current ? reviewRef.current : null
      if (target?.status !== 'PENDING') {
        const d = await getDraft(journeyId)
        target = d.latest_review?.status === 'PENDING' ? d.latest_review : null
      }
      if (target) await withdrawReview(journeyId, target.review_id, reason)
      apply(await getDraft(journeyId))
    },
    [journeyId, apply],
  )

  // ---------- 驗證與送審 ----------
  const validate = useCallback(async () => {
    setValidating(true)
    try {
      if (!(await flush())) return
      const r = await validateDraft(journeyId)
      setReport(r)
      setReportOpen(true)
    } catch (e) {
      setBanner({ kind: 'saveFailed', requestId: asError(e).requestId })
    } finally {
      setValidating(false)
    }
  }, [flush, journeyId])

  const submit = useCallback(async (note: string): Promise<boolean> => {
    if (!(await flush())) return false
    try {
      const r = await submitDraft(journeyId, revRef.current, note || undefined)
      lockedRef.current = true
      reviewRef.current = r
      setReview(r)
      return true
    } catch (e) {
      const err = asError(e)
      if (err.status === 400 && err.code === 'VALIDATION_FAILED') {
        const errors: Issue[] = err.issues
        setReport({ valid: false, errors, warnings: [] })
        setBanner({ kind: 'submitBlocked', count: errors.length })
      } else if (err.status === 409 && err.code === 'DRAFT_CONFLICT') {
        setBanner({ kind: 'conflict', by: null, at: null })
        getDraft(journeyId).then(
          (d) => setBanner({ kind: 'conflict', by: d.updated_by, at: d.updated_at }),
          () => undefined,
        )
      } else if (err.status === 409 && err.code === 'REVIEW_PENDING') {
        lockedRef.current = true
        setBanner({ kind: 'inReview' })
      } else if (err.status === 409 && err.code === 'JOURNEY_ABORTED') {
        setBanner({ kind: 'aborted', detail: null })
        getJourney(journeyId).then(
          (j) => setBanner({ kind: 'aborted', detail: j }),
          () => undefined,
        )
      } else {
        setBanner({ kind: 'submitFailed', requestId: err.requestId })
      }
      return false
    }
  }, [flush, journeyId])

  // ---------- 欄位建議（field-keys）：載入時與進入事件名稱改變時更新 ----------
  const eventName = def ? entryEventName(def) : null
  const keysFetched = useRef(false)
  useEffect(() => {
    if (eventName === null) return
    let cancelled = false
    // 載入後第一次立刻取；之後事件名稱改變（打字中）才等 400ms
    const wait = eventName && keysFetched.current ? 400 : 0
    keysFetched.current = true
    const t = window.setTimeout(() => {
      getFieldKeys(eventName || undefined).then(
        (k) => !cancelled && setFieldKeys(k),
        () => undefined,
      )
    }, wait)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [eventName])

  return {
    phase, loadError, reload, def, layout, meta, detail, review, dirty, saving, savedAt, banner, setBanner,
    report, setReport, reportOpen, setReportOpen, validating, fieldKeys,
    saveError, save, edit, flush, loadLatest, withdraw, validate, submit,
  }
}
