import { describe, expect, it } from 'vitest'
import { ApiError } from '../../../src/api/client'
import type { CurrentUser, JourneyDetail, Review } from '../../../src/api/types'
import {
  ADMIN_ONLY, LOCKED, NOT_OWNER, OWN_APPROVE, VIEW_ONLY, describeError, diffNodes, headerReason, journeyActions, reviewPageActions,
  reviewRow, stopRow, waitText,
} from '../../../src/pages/review/model'

const me = (role: CurrentUser['role'], id = 'u1'): CurrentUser => ({ user_id: id, email: 'a@b.c', display_name: 'A', role })
const ref = (id: string, n = 'X') => ({ user_id: id, display_name: n })
const detail = (over: Partial<JourneyDetail> = {}): JourneyDetail => ({
  journey_id: 'j', name: 'n', status: 'ACTIVE', latest_version: 3, has_draft: false, review_status: null, updated_at: '', published_at: null,
  description: null, versions: [], stopped_at: null, stop_reason: null, stopped_by: null, latest_review: null, ...over,
})
const brief = (by: string) => ({ review_id: 'r', status: 'PENDING' as const, submitted_by: ref(by), submitted_at: '', decided_by: null, decided_at: null, reason: null })
const rv = (over: Partial<Review> = {}): Review => ({
  ...brief('u2'), journey_id: 'j', action: 'PUBLISH', revision: 4, base_version: 3, note: null, version: null, ...over,
})
const labels = (d: JourneyDetail, u: CurrentUser) => journeyActions(d, u).map((a) => `${a.label}${a.disabled ? '(x)' : ''}`)

describe('journeyActions', () => {
  it('已停止沒有動作', () => expect(journeyActions(detail({ status: 'ABORTED' }), me('ADMIN'))).toEqual([]))
  it('檢視者全部停用並說明', () => {
    const a = journeyActions(detail(), me('VIEWER'))
    expect(a.every((x) => x.disabled === VIEW_ONLY)).toBe(true)
  })
  it('進行中沒有草稿 / 有草稿', () => {
    expect(labels(detail(), me('EDITOR'))).toEqual(['編輯', '停止旅程'])
    expect(labels(detail({ has_draft: true }), me('EDITOR'))).toEqual(['編輯', '捨棄草稿', '停止旅程'])
  })
  it('從未發佈（DRAFT）：不顯示停止與捨棄草稿，改有可用的「刪除旅程」（收在更多動作）', () => {
    const a = journeyActions(detail({ status: 'DRAFT', has_draft: true, latest_version: null }), me('EDITOR'))
    expect(a.map((x) => x.label)).toEqual(['編輯', '刪除旅程'])
    expect(a.some((x) => x.disabled)).toBe(false)
    expect(a.find((x) => x.key === 'delete')?.more).toBe(true)
    expect(a.find((x) => x.key === 'edit')?.more).toBeUndefined()
  })
  it('已發佈過的旅程沒有刪除旅程；停止與捨棄草稿收在更多動作', () => {
    const a = journeyActions(detail({ has_draft: true }), me('ADMIN'))
    expect(a.some((x) => x.key === 'delete')).toBe(false)
    expect(a.filter((x) => x.more).map((x) => x.key)).toEqual(['discard', 'stop'])
  })
  it('檢視者看從未發佈：編輯與刪除旅程停用並說明角色，沒有停止', () => {
    const a = journeyActions(detail({ status: 'DRAFT', latest_version: null }), me('VIEWER'))
    expect(a.map((x) => x.key)).toEqual(['edit', 'delete'])
    expect(a.every((x) => x.disabled === VIEW_ONLY)).toBe(true)
  })
  it('送審中的從未發佈草稿：編輯、刪除旅程因鎖定停用，沒有停止與捨棄草稿', () => {
    const a = journeyActions(detail({ status: 'DRAFT', latest_version: null, has_draft: true, review_status: 'PENDING', latest_review: brief('u1') }), me('EDITOR'))
    expect(a.map((x) => x.key)).toEqual(['withdraw', 'edit', 'delete'])
    expect(a.filter((x) => x.disabled).map((x) => x.disabled)).toEqual([LOCKED, LOCKED])
  })
  it('已退回的從未發佈草稿：修改後再送審＋刪除旅程（沒有捨棄草稿、停止）', () => {
    const a = journeyActions(detail({ status: 'DRAFT', latest_version: null, has_draft: true, review_status: 'REJECTED' }), me('EDITOR'))
    expect(a.map((x) => x.key)).toEqual(['resubmit', 'delete'])
  })
  it('已退回', () => expect(labels(detail({ has_draft: true, review_status: 'REJECTED' }), me('EDITOR'))[0]).toBe('修改後再送審'))
  it('送審中：編輯者撤回別人的停用；管理員可核准別人、不可核准自己', () => {
    const d = (by: string) => detail({ has_draft: true, review_status: 'PENDING', latest_review: brief(by) })
    expect(journeyActions(d('u2'), me('EDITOR')).find((x) => x.key === 'withdraw')?.disabled).toBe(NOT_OWNER)
    expect(journeyActions(d('u1'), me('EDITOR')).find((x) => x.key === 'withdraw')?.disabled).toBeUndefined()
    expect(journeyActions(d('u2'), me('EDITOR')).some((x) => x.key === 'approve')).toBe(false)
    expect(journeyActions(d('u2'), me('ADMIN')).find((x) => x.key === 'approve')?.disabled).toBeUndefined()
    expect(journeyActions(d('u1'), me('ADMIN')).find((x) => x.key === 'approve')?.disabled).toBe(OWN_APPROVE)
  })
})

describe('headerReason：標頭唯一一行停用原因', () => {
  it('沒有停用動作 → null', () => expect(headerReason(journeyActions(detail(), me('EDITOR')))).toBeNull())
  it('檢視者的多個停用動作共用同一句', () => expect(headerReason(journeyActions(detail(), me('VIEWER')))).toBe(VIEW_ONLY))
  it('送審中：非送審者的撤回與鎖定原因去重後併成一行', () => {
    const d = detail({ status: 'DRAFT', latest_version: null, has_draft: true, review_status: 'PENDING', latest_review: brief('u2') })
    expect(headerReason(journeyActions(d, me('EDITOR')))).toBe(`${NOT_OWNER}${LOCKED}`)
  })
})

describe('reviewPageActions', () => {
  it('非管理員停用、管理員自己送的不能核准', () => {
    expect(reviewPageActions(rv(), me('EDITOR')).map((a) => a.disabled)).toEqual([ADMIN_ONLY, ADMIN_ONLY])
    expect(reviewPageActions(rv(), me('ADMIN')).map((a) => a.disabled)).toEqual([undefined, undefined])
    expect(reviewPageActions(rv({ submitted_by: ref('u1') }), me('ADMIN'))[1].disabled).toBe(OWN_APPROVE)
  })
})

describe('describeError', () => {
  const e = (status: number, code: string | null, issues = 0) =>
    new ApiError(status, code, null, undefined, null, Array.from({ length: issues }, () => ({ field: '', message: '', rule: null, node_id: null })))
  it.each([
    [e(409, 'REVIEW_NOT_PENDING'), true, /已被其他管理員處理/],
    [e(404, 'REVIEW_NOT_FOUND'), true, /找不到/],
    [e(400, 'VALIDATION_FAILED', 2), false, /2 個錯誤.*維持「審核中」/],
    [e(400, 'VALIDATION_FAILED'), false, /驗證失敗/],
    [e(403, 'SELF_APPROVAL_FORBIDDEN'), false, /自己送審/],
    [e(403, 'FORBIDDEN'), false, /沒有權限/],
    [e(409, 'JOURNEY_ABORTED'), true, /已停止/],
    [e(409, 'DRAFT_IN_REVIEW'), true, /已送審/],
    [e(409, 'JOURNEY_NOT_PUBLISHED'), false, /尚未發佈/],
    [e(500, null), false, /操作失敗/],
  ])('%#', (err, reload, re) => {
    const d = describeError(err)
    expect(d.reload).toBe(reload)
    expect(d.text).toMatch(re)
  })
  it('非 ApiError 當連線失敗', () => expect(describeError(new Error('x')).text).toMatch(/操作失敗/))
})

describe('waitText', () => {
  const now = new Date('2026-11-07T04:00:00Z')
  it.each([
    ['2026-11-07T03:30:00Z', '等待不到 1 小時'],
    ['2026-11-07T02:00:00Z', '等待 2 小時'],
    ['2026-11-06T04:00:00Z', '等待 1 天'],
    ['2026-11-05T04:00:00Z', '等待 2 天'],
    ['bad', '等待不到 1 小時'],
  ])('%s', (iso, text) => expect(waitText(iso, now)).toBe(text))
})

describe('diffNodes', () => {
  const n = (id: string, name: string | null, config: Record<string, unknown> = {}) => ({ id, type: 'EndNode', name, config, outputs: {} })
  const draft = (nodes: ReturnType<typeof n>[]) => ({ name: 'x', description: null, settings: {}, entry_node: 's', nodes })
  it('新增、修改、刪除；config 鍵順序不同不算修改', () => {
    const base = { nodes: [n('a', 'A', { x: 1, y: 2 }), n('b', 'B'), n('c', 'C')] }
    const r = diffNodes(base, draft([n('a', 'A', { y: 2, x: 1 }), n('b', 'B2'), n('d', 'D')]))
    expect(r.map((c) => `${c.id}:${c.change}`)).toEqual(['b:modified', 'd:added', 'c:removed'])
  })
  it('沒有前一版 → 全部新增', () => {
    expect(diffNodes(null, draft([n('a', null)])).map((c) => c.change)).toEqual(['added'])
  })
})

describe('reviewRow / stopRow', () => {
  it('各狀態', () => {
    expect(reviewRow(rv({ note: '說明' }))).toMatchObject({ who: 'X', action: '送審（基於 v3）', detail: '說明：說明' })
    expect(reviewRow(rv({ status: 'APPROVED', decided_by: ref('a', 'W'), decided_at: 't', version: 4, reason: '好' }))).toMatchObject({ who: 'W', action: '核准並發佈 v4', detail: '備註：好' })
    expect(reviewRow(rv({ status: 'APPROVED', submitted_by: null, decided_by: null, version: 3, reason: '管理金鑰建立', base_version: null }))).toMatchObject({ who: '管理金鑰', action: '建立 v3', detail: '原因：管理金鑰建立' })
    expect(reviewRow(rv({ status: 'REJECTED', decided_by: ref('a', 'W'), reason: '不行' }))).toMatchObject({ action: '退回（基於 v3）', detail: '原因：不行' })
    expect(reviewRow(rv({ status: 'WITHDRAWN', decided_by: ref('a', 'W'), reason: null }))).toMatchObject({ action: '撤回送審（基於 v3）', detail: null })
    expect(reviewRow(rv({ status: 'WITHDRAWN', decided_by: ref('a', 'W'), reason: '旅程已停止' })).action).toContain('因旅程停止')
  })
  it('停止列', () => {
    expect(stopRow(detail())).toBeNull()
    expect(stopRow(detail({ status: 'ABORTED', stopped_at: 't', stop_reason: '結束', stopped_by: ref('a', 'W') }))).toMatchObject({ who: 'W', action: '停止旅程', detail: '原因：結束' })
    expect(stopRow(detail({ status: 'ABORTED', stopped_at: 't' }))).toMatchObject({ who: '管理金鑰', detail: null })
  })
})
