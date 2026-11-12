// 草稿狀態與自動儲存：編輯後停手 SAVE_DELAY 毫秒再 PUT；同時只送一個請求；revision 取自上一次回應。
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, getDraft, getFieldKeys, getJourney, putDraft, submitDraft, validateDraft, withdrawReview } from '../api/client'
import type { Draft, FieldKeys, Issue, JourneyDetail, UserRef, ValidationReport } from '../api/types'
import { entryEventName, fillLayout, type Def, type Layout } from './model'

export const SAVE_DELAY = 1000

export type Banner =
  | { kind: 'conflict'; by: UserRef | null; at: string | null }
  | { kind: 'inReview' }
  | { kind: 'aborted'; detail: JourneyDetail | null }
  | { kind: 'saveFailed'; requestId: string | null }
  | { kind: 'submitBlocked'; count: number }
  | { kind: 'submitFailed'; requestId: string | null }

const asError = (e: unknown) => (e instanceof ApiError ? e : new ApiError(0, null, null))

export function useDraftEditor(journeyId: string) {
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading')
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [def, setDef] = useState<Def | null>(null)
  const [layout, setLayout] = useState<Layout | null>(null)
  const [meta, setMeta] = useState<{ updatedAt: string; updatedBy: UserRef | null } | null>(null)
  const [detail, setDetail] = useState<JourneyDetail | null>(null)
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)
  const [savedAt, setSavedAt] = useState<string | null>(null)
  const [banner, setBanner] = useState<Banner | null>(null)
  const [report, setReport] = useState<ValidationReport | null>(null)
  const [reportOpen, setReportOpen] = useState(false)
  const [validating, setValidating] = useState(false)
  const [fieldKeys, setFieldKeys] = useState<FieldKeys | null>(null)
  const [attempt, setAttempt] = useState(0)

  const defRef = useRef<Def | null>(null)
  const layoutRef = useRef<Layout | null>(null)
  const revRef = useRef(0)
  const dirtyRef = useRef(false)
  const haltRef = useRef(false)
  const timer = useRef<number | null>(null)
  const inflight = useRef<Promise<void> | null>(null)
  const alive = useRef(true)

  const apply = useCallback((d: Draft) => {
    defRef.current = d.definition
    layoutRef.current = d.layout
    revRef.current = d.revision
    dirtyRef.current = false
    haltRef.current = false
    setDef(d.definition)
    setLayout(d.layout)
    setMeta({ updatedAt: d.updated_at, updatedBy: d.updated_by })
    setDirty(false)
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

  // ---------- 儲存 ----------
  const saveRef = useRef<() => Promise<void>>(() => Promise.resolve())
  const schedule = useCallback(() => {
    if (timer.current !== null) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => {
      timer.current = null
      void saveRef.current()
    }, SAVE_DELAY)
  }, [])
  const save = useCallback((): Promise<void> => {
    if (inflight.current) return inflight.current
    if (haltRef.current || !dirtyRef.current || !defRef.current) return Promise.resolve()
    const definition = defRef.current
    const full = fillLayout(definition, layoutRef.current)
    dirtyRef.current = false
    setSaving(true)
    const run = (async () => {
      try {
        const res = await putDraft(journeyId, { revision: revRef.current, definition, layout: full })
        revRef.current = res.revision
        if (alive.current) {
          setMeta({ updatedAt: res.updated_at, updatedBy: res.updated_by })
          setSavedAt(res.updated_at ?? new Date().toISOString())
        }
      } catch (e) {
        dirtyRef.current = true
        haltRef.current = true
        if (!alive.current) return
        const err = asError(e)
        if (err.status === 409 && err.code === 'DRAFT_CONFLICT') {
          setBanner({ kind: 'conflict', by: null, at: null })
          getDraft(journeyId).then(
            (d) => alive.current && setBanner({ kind: 'conflict', by: d.updated_by, at: d.updated_at }),
            () => undefined,
          )
        } else if (err.status === 409 && err.code === 'DRAFT_IN_REVIEW') {
          setBanner({ kind: 'inReview' })
        } else if (err.status === 409 && err.code === 'JOURNEY_ABORTED') {
          setBanner({ kind: 'aborted', detail: null })
          getJourney(journeyId).then(
            (j) => alive.current && setBanner({ kind: 'aborted', detail: j }),
            () => undefined,
          )
        } else if (err.status !== 401) {
          setBanner({ kind: 'saveFailed', requestId: err.requestId })
        }
      } finally {
        inflight.current = null
        if (alive.current) {
          setSaving(false)
          setDirty(dirtyRef.current)
          if (dirtyRef.current && !haltRef.current) schedule()
        }
      }
    })()
    inflight.current = run
    return run
  }, [journeyId, schedule])

  useEffect(() => {
    saveRef.current = save
  }, [save])

  /** 立刻存完（驗證、送審前）；回傳是否已無未儲存內容 */
  const flush = useCallback(async () => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current)
      timer.current = null
    }
    if (inflight.current) await inflight.current
    if (dirtyRef.current && !haltRef.current) await save()
    return !dirtyRef.current
  }, [save])

  const edit = useCallback(
    (fn: (d: Def, l: Layout) => [Def, Layout]) => {
      if (!defRef.current) return
      const [d, l] = fn(defRef.current, fillLayout(defRef.current, layoutRef.current))
      defRef.current = d
      layoutRef.current = l
      dirtyRef.current = true
      setDef(d)
      setLayout(l)
      setDirty(true)
      if (!haltRef.current) schedule()
    },
    [schedule],
  )

  const retrySave = useCallback(() => {
    haltRef.current = false
    setBanner(null)
    void save()
  }, [save])

  useEffect(() => {
    const online = () => {
      if (banner?.kind === 'saveFailed') retrySave()
    }
    window.addEventListener('online', online)
    return () => window.removeEventListener('online', online)
  })

  useEffect(
    () => () => {
      alive.current = false
      if (timer.current !== null) window.clearTimeout(timer.current)
      if (dirtyRef.current && !haltRef.current) void save()
    },
    [save],
  )

  const loadLatest = useCallback(async () => {
    try {
      apply(await getDraft(journeyId))
    } catch (e) {
      const err = asError(e)
      setBanner({ kind: 'saveFailed', requestId: err.requestId })
    }
  }, [journeyId, apply])

  const withdraw = useCallback(async () => {
    try {
      const d = await getDraft(journeyId)
      if (d.latest_review?.status === 'PENDING') await withdrawReview(journeyId, d.latest_review.review_id)
      apply(await getDraft(journeyId))
    } catch (e) {
      setBanner({ kind: 'saveFailed', requestId: asError(e).requestId })
    }
  }, [journeyId, apply])

  // ---------- 驗證與送審 ----------
  const validate = useCallback(async () => {
    setValidating(true)
    try {
      await flush()
      const r = await validateDraft(journeyId)
      setReport(r)
      setReportOpen(true)
    } catch (e) {
      setBanner({ kind: 'saveFailed', requestId: asError(e).requestId })
    } finally {
      setValidating(false)
    }
  }, [flush, journeyId])

  const submit = useCallback(async (): Promise<boolean> => {
    await flush()
    try {
      await submitDraft(journeyId, revRef.current)
      return true
    } catch (e) {
      const err = asError(e)
      if (err.status === 400 && err.code === 'VALIDATION_FAILED') {
        const errors: Issue[] = err.issues
        setReport({ valid: false, errors, warnings: [] })
        setBanner({ kind: 'submitBlocked', count: errors.length })
      } else if (err.status === 409 && err.code === 'DRAFT_CONFLICT') {
        haltRef.current = true
        setBanner({ kind: 'conflict', by: null, at: null })
        getDraft(journeyId).then(
          (d) => setBanner({ kind: 'conflict', by: d.updated_by, at: d.updated_at }),
          () => undefined,
        )
      } else if (err.status === 409 && err.code === 'REVIEW_PENDING') {
        haltRef.current = true
        setBanner({ kind: 'inReview' })
      } else if (err.status === 409 && err.code === 'JOURNEY_ABORTED') {
        haltRef.current = true
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
  useEffect(() => {
    if (eventName === null) return
    let cancelled = false
    const t = window.setTimeout(() => {
      getFieldKeys(eventName || undefined).then(
        (k) => !cancelled && setFieldKeys(k),
        () => undefined,
      )
    }, eventName ? 400 : 0)
    return () => {
      cancelled = true
      window.clearTimeout(t)
    }
  }, [eventName])

  return {
    phase, loadError, reload, def, layout, meta, detail, dirty, saving, savedAt, banner, setBanner,
    report, setReport, reportOpen, setReportOpen, validating, fieldKeys,
    edit, flush, retrySave, loadLatest, withdraw, validate, submit,
  }
}
