/**
 * journey-review-ui（FE-06）驗收測試共用 fixture（qa 撰寫，worker 不可改）。
 * 後端依 docs/contracts/journeys.md §1、§3（草稿、送審、審核、停止）、auth.md §2 mock；
 * UI 文案與結構依 docs/ui/journey-builder.md 與設計稿 index.html 的 rv-* 畫面（按鈕文字、對話框標題、提示文字照設計稿）。
 *
 * 對 worker 的假設（規格沒寫死、測試必須定下來的，全部列在這裡）：
 *   - 進入點、fetch、時區、polyfill 同 journey-canvas-ui／journey-customer-ui fixtures。
 *   - 網址：旅程頁 `/journeys/<id>`、編輯畫布 `/journeys/<id>/edit`、新建草稿 `/journeys/new`（canvas-ui 已定）；
 *     管理員審核頁固定為 `/journeys/<id>/review`（REVIEW_PATH，設計稿 rv-review）；其餘（審核紀錄頁）從旅程頁點「審核紀錄」進入，不假設網址。
 *   - 旅程頁（JourneyPage）的頁首按鈕列依 `rv-matrix`／`rv-bars`：按鈕、連結、「更多動作」收合選單裡的選單項都算「動作」，
 *     以可存取名稱（aria-label 或文字）完全相等比對；「更多動作」按鈕（journey-header-save-delete 起 aria-label 固定為「更多動作」）
 *     不再列出收在裡面的動作，測試找不到時會依序點開各個「更多動作」再找。
 *     停用鈕 = `aria-disabled="true"`（不可用原生 disabled，要能聚焦）＋ `aria-describedby` 指向原因文字；點下去不得開對話框或送請求。
 *   - 旅程頁「審核紀錄」連結（或按鈕）在所有狀態、所有角色都存在（含檢視者；規格矩陣只列部分狀態，但紀錄頁檢視者以上都能看）。
 *   - 旅程頁以 `JourneyDetail`（含 `review_status`、`latest_review`）決定按鈕；核准／退回／撤回用 `latest_review.review_id`。
 *     「核准並發佈 vN」的 N = `latest_version + 1`（= 草稿 revision 的設計稿寫法；fixture 兩者同為 4）。
 *   - 審核頁：變更摘要由前端比對 `GET …/draft` 與 `GET …/versions/<base_version>`（不需新 API）；
 *     比對單位是節點（依 id）：新增＝只在草稿有、刪除＝只在版本有、修改＝同 id 但 name 或 config 不同（outputs 不算，fixture 不動 outputs）。
 *   - 審核紀錄：每筆 `Review` 一列（契約 §3.2 末條），文字「<操作者> <動作>」：核准「核准並發佈 vN」、退回「退回」、撤回「撤回送審」、
 *     送審中「送審」；管理金鑰建立（`submitted_by` null）顯示「管理金鑰 建立 vN」；已停止旅程另併一列「<停止者> 停止旅程」（來自 JourneyDetail）。
 *     不假設時間顯示格式。紀錄列在 `ol > li`（排除麵包屑 nav 與有 aria-label 的版本流程）。
 *   - 列表「待審核」「已退回」篩選鈕：名稱以該詞開頭（後面可帶數量）、`aria-pressed`；點下去送 `GET /api/v1/journeys?review_status=PENDING|REJECTED`。
 *     「送審者與等待時間」欄的等待時間以現在時間 − `latest_review.submitted_at` 計：<48h 以小時、之後以天，格式「等待 N 小時」「等待 N 天」；
 *     測試只用整 2 小時、整 1 天、整 2 天（固定系統時間）。
 *   - 編輯畫布頁（`/edit`）：頁首「送審」按鈕開對話框（`rv-dialogs` 第一個）；送審中（`Draft.latest_review.status = PENDING`）草稿鎖定，
 *     顯示「已送審，等待管理員審核」「草稿已鎖定，不能修改」與「撤回送審」，沒有「送審」鈕。
 */
import { act } from '@testing-library/react'
import { expect, vi } from 'vitest'
import {
  AMY, JID, backend, bodyText, cleanup, deferred, journeyDetail, json, problem,
  resetEnv, screen, start, teardown, user, userEvent, waitFor, within,
} from '../journey-canvas-ui/fixtures'
import type { Call } from '../journey-canvas-ui/fixtures'
import { gotoNav, journeySummary, journeyVersion, page, params } from '../journey-customer-ui/fixtures'
import type { Api } from '../journey-customer-ui/fixtures'

export { AMY, JID, backend, bodyText, cleanup, deferred, gotoNav, journeyDetail, journeySummary, journeyVersion, json, page, params, problem, resetEnv, screen, start, teardown, user, userEvent, waitFor, within, act }
export type { Api, Call }

export const REVIEW_PATH = `/journeys/${JID}/review`
export const JOURNEY_URL = `/journeys/${JID}`
export const API = `/api/v1/journeys/${JID}`
export const RID = '01JD5REVIEW0000000000001'

export const ref = (id: string, name: string) => ({ user_id: id, display_name: name })
export const WANG = user({ user_id: '01JD0USER0000000000000003', email: 'wang@example.com', display_name: '王小華', role: 'ADMIN' })
export const VIEWER = user({ user_id: '01JD0USER0000000000000004', email: 'lin@example.com', display_name: '林小雅', role: 'VIEWER' })
export const REF_AMY = ref(AMY.user_id, AMY.display_name)
export const REF_WANG = ref(WANG.user_id, WANG.display_name)
export const REF_DAMING = ref('01JD0USER0000000000000002', '王大明')

export const NOTE = '新增 VIP 分流與再次提醒，等待改 24 小時。'

export function review(over: Record<string, unknown> = {}) {
  return {
    review_id: RID, journey_id: JID, action: 'PUBLISH', status: 'PENDING', revision: 4, base_version: 3, note: NOTE,
    submitted_by: REF_AMY, submitted_at: '2026-11-07T02:20:00Z', decided_by: null, decided_at: null, reason: null, version: null, ...over,
  }
}
export const brief = (r: Record<string, unknown>) => {
  const { review_id, status, submitted_by, submitted_at, decided_by, decided_at, reason } = r
  return { review_id, status, submitted_by, submitted_at, decided_by, decided_at, reason }
}
export const rejected = (over: Record<string, unknown> = {}) =>
  review({ status: 'REJECTED', decided_by: REF_WANG, decided_at: '2026-11-06T08:30:00Z', reason: '等待時間請改為 2 天', ...over })

export type Kind = 'active' | 'editing' | 'neverPublished' | 'pending' | 'rejected' | 'aborted'

/** 一種旅程狀態的 JourneyDetail 與 Draft；pending／rejected 的送審者由 submitter 指定 */
export function scenario(kind: Kind, submitter = REF_AMY) {
  let detail: Record<string, unknown> = journeyDetail({ journey_id: JID })
  let draft: Record<string, unknown> | null = null
  let rv: Record<string, unknown> | null = null
  const mkDraft = (latest_review: unknown) => ({
    journey_id: JID, revision: 4, definition: draftDef(), layout: null, updated_at: '2026-11-07T02:00:00Z', updated_by: REF_AMY, latest_review,
  })
  switch (kind) {
    case 'active':
      break
    case 'editing':
      detail = journeyDetail({ has_draft: true })
      draft = mkDraft(null)
      break
    case 'neverPublished':
      detail = journeyDetail({ status: 'DRAFT', latest_version: null, published_at: null, has_draft: true, versions: [] })
      draft = mkDraft(null)
      break
    case 'pending':
      rv = review({ submitted_by: submitter })
      detail = journeyDetail({ has_draft: true, review_status: 'PENDING', latest_review: brief(rv) })
      draft = mkDraft(rv)
      break
    case 'rejected':
      rv = rejected({ submitted_by: submitter })
      detail = journeyDetail({ has_draft: true, review_status: 'REJECTED', latest_review: brief(rv) })
      draft = mkDraft(rv)
      break
    case 'aborted':
      detail = journeyDetail({
        status: 'ABORTED', stopped_at: '2026-11-06T09:05:00Z', stop_reason: '活動結束', stopped_by: REF_WANG,
      })
      break
  }
  return { detail, draft, review: rv }
}

/** v3 的節點（journey-customer-ui 的 journeyVersion）＋ 草稿 v4：新增 2 個、修改 1 個（等待時間）、刪除 0 個 */
export function draftDef() {
  const v = journeyVersion(3, '等 3 天')
  const nodes: Array<{ id: string; type: string; name: string | null; config: Record<string, unknown>; outputs: Record<string, unknown> }> = v.nodes.map((n) => (n.id === 'wait_1' ? { ...n, config: { duration: 'PT24H' } } : n))
  nodes.push(
    { id: 'cond_vip', type: 'ConditionNode', name: '是 VIP？', config: { condition: { op: 'AND', children: [{ type: 'attribute', field: 'tier', operator: 'EQ', value: 'VIP' }] } }, outputs: {} },
    { id: 'send_vip', type: 'SendMessageNode', name: 'VIP 專屬關心', config: { channel_id: 'mock_default', template_id: 'cart_vip' }, outputs: {} },
  )
  return { name: '棄單挽回', description: null, settings: {}, entry_node: 'start', nodes }
}

export interface World {
  detail: Record<string, unknown>
  draft: Record<string, unknown> | null
  reviews: Record<string, unknown>[]
}
type Handler = (c: Call) => Response | Promise<Response>

/**
 * 有狀態的旅程後端：旅程頁、審核頁、紀錄頁共用。核准／退回／撤回／停止預設成功並更新 world（routes 可覆寫成失敗）。
 */
export function journeyBackend(opts: {
  me?: ReturnType<typeof user>; kind?: Kind; submitter?: typeof REF_AMY; detail?: Record<string, unknown>
  draft?: Record<string, unknown> | null; reviews?: Record<string, unknown>[]; routes?: Record<string, Handler>
} = {}) {
  const s = scenario(opts.kind ?? 'active', opts.submitter)
  const world: World = {
    detail: { ...s.detail, ...opts.detail },
    draft: opts.draft === undefined ? s.draft : opts.draft,
    reviews: opts.reviews ?? (s.review ? [s.review] : []),
  }
  const setDetail = (o: Record<string, unknown>) => { world.detail = { ...world.detail, ...o } }
  const findPending = (): Record<string, unknown> | null => world.reviews.find((r) => r.status === 'PENDING') ?? (world.detail.latest_review as Record<string, unknown> | null)
  const decide = (status: string, extra: Record<string, unknown> = {}) => (c: Call) => {
    const base = findPending() ?? review()
    const decided: Record<string, unknown> = { ...base, status, decided_by: REF_WANG, decided_at: '2026-11-07T03:00:00Z', reason: (c.body as { reason?: string })?.reason ?? null, ...extra }
    world.reviews = [decided, ...world.reviews.filter((r) => r.review_id !== decided.review_id)]
    return decided
  }
  const api = backend({
    [`GET ${API}`]: () => json(world.detail),
    [`GET ${API}/draft`]: () => (world.draft ? json(world.draft) : problem(404, 'DRAFT_NOT_FOUND')),
    [`PUT ${API}/draft`]: (c) => {
      const b = c.body as { revision: number; definition: unknown; layout: unknown }
      const rev = world.draft ? (world.draft.revision as number) : 0
      if (b.revision !== rev) return problem(409, 'DRAFT_CONFLICT')
      world.draft = { journey_id: JID, revision: rev + 1, definition: b.definition, layout: b.layout, updated_at: '2026-11-07T03:00:00Z', updated_by: REF_AMY, latest_review: null }
      setDetail({ has_draft: true })
      return json(world.draft)
    },
    [`GET ${API}/versions/3`]: () => json(journeyVersion(3, '等 3 天')),
    [`GET ${API}/reviews`]: () => json(page(world.reviews)),
    [`POST ${API}/reviews/${RID}/approve`]: (c) => {
      const r = decide('APPROVED', { version: 4 })(c)
      world.draft = null
      setDetail({ latest_version: 4, has_draft: false, review_status: null, latest_review: null, versions: [{ version: 4, published_at: '2026-11-07T03:00:00Z' }, ...(world.detail.versions as unknown[])] })
      return json(r)
    },
    [`POST ${API}/reviews/${RID}/reject`]: (c) => {
      const r = decide('REJECTED')(c)
      setDetail({ review_status: 'REJECTED', latest_review: brief(r) })
      if (world.draft) world.draft = { ...world.draft, latest_review: r }
      return json(r)
    },
    [`POST ${API}/reviews/${RID}/withdraw`]: (c) => {
      const r = decide('WITHDRAWN')(c)
      setDetail({ review_status: null, latest_review: null })
      if (world.draft) world.draft = { ...world.draft, latest_review: null }
      return json(r)
    },
    [`POST ${API}/stop`]: (c) => {
      setDetail({
        status: 'ABORTED', stopped_at: '2026-11-07T03:10:00Z', stop_reason: (c.body as { reason: string }).reason, stopped_by: REF_WANG,
        review_status: null, latest_review: null,
      })
      return json(world.detail)
    },
    ...opts.routes,
  }, opts.me ?? AMY)
  return { api, world, setDetail }
}

export const callsTo = (api: Api, method: string, path: string) => api.calls.filter((c) => c.method === method && c.path === path)

// ---------- 動作（按鈕／連結／選單項）查找 ----------
const nameOf = (el: Element) => (el.getAttribute('aria-label') ?? el.textContent ?? '').replace(/\s+/g, ' ').trim()
const ACTION_SEL = 'button, a, [role="menuitem"], [role="button"]'
const outsideDialog = (el: Element) => !el.closest('[role="dialog"]')
// 選單項（role=menuitem）可帶一行小字說明（設計稿「刪除旅程／刪掉整個旅程，無法復原」），名稱以動作名稱開頭即算
const nameIs = (e: Element, name: string) => nameOf(e) === name || (e.getAttribute('role') === 'menuitem' && nameOf(e).startsWith(name))
const find = (name: string) => [...document.querySelectorAll<HTMLElement>(ACTION_SEL)].filter(outsideDialog).find((e) => nameIs(e, name)) ?? null
/** 「⋯ 更多」按鈕（canvas-interaction.md §4：aria-label「更多動作」，收刪除旅程／停止旅程／捨棄草稿） */
const overflows = () => [...document.querySelectorAll<HTMLElement>('button[aria-label^="更多"]')]

/** 找動作；不在頁首就依序點開「更多動作」選單（aria-label 不再列出收在裡面的動作）。找不到回 null */
export async function action(name: string): Promise<HTMLElement | null> {
  const hit = find(name)
  if (hit) return hit
  for (const o of overflows()) {
    if (o.getAttribute('aria-expanded') !== 'true') await userEvent.click(o)
    const again = find(name)
    if (again) return again
  }
  return null
}
export async function mustAction(name: string) {
  const el = await action(name)
  if (!el) throw new Error(`找不到動作「${name}」；畫面上：${[...document.querySelectorAll(ACTION_SEL)].map(nameOf).join('｜')}`)
  return el
}
export const isDisabled = (el: Element) => el.getAttribute('aria-disabled') === 'true' || (el as HTMLButtonElement).disabled === true
export function reasonOf(el: Element): string {
  const ids = (el.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
  return ids.map((i) => document.getElementById(i)?.textContent ?? '').join(' ').replace(/\s+/g, ' ').trim()
}
/** 停用鈕：aria-disabled（可聚焦、非原生 disabled）、原因文字在 aria-describedby */
export async function expectDisabled(name: string, reason: string | RegExp) {
  const el = await mustAction(name)
  expect(el.getAttribute('aria-disabled'), `「${name}」應為 aria-disabled`).toBe('true')
  expect((el as HTMLButtonElement).disabled, `「${name}」不可用原生 disabled`).toBeFalsy()
  const r = reasonOf(el)
  if (typeof reason === 'string') expect(r, `「${name}」的停用原因`).toContain(reason)
  else expect(r).toMatch(reason)
  return el
}
export async function expectEnabled(name: string) {
  const el = await mustAction(name)
  expect(isDisabled(el), `「${name}」應可用`).toBe(false)
  return el
}
export async function expectAbsent(name: string) {
  expect(await action(name), `不應出現「${name}」`).toBeNull()
}
/** 沒有可用的這個動作（不存在或停用都算） */
export async function expectNotUsable(name: string) {
  const el = await action(name)
  if (el) expect(isDisabled(el), `「${name}」不應可用`).toBe(true)
}

export const dialog = (name: string | RegExp) => screen.findByRole('dialog', { name })
export const inDialog = (d: HTMLElement, role: 'button' | 'textbox', name: string | RegExp) => within(d).getByRole(role, { name })
export const closeOverflow = async () => {
  for (const o of overflows()) if (o.getAttribute('aria-expanded') === 'true') await userEvent.click(o)
}

export async function openJourney(o: Parameters<typeof journeyBackend>[0] = {}) {
  const b = journeyBackend(o)
  start(JOURNEY_URL)
  await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
  return b
}

/** 固定「現在」只影響 Date（不動計時器），其餘照常 */
export function freezeNow(iso: string) {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date(iso))
}
export const unfreeze = () => vi.useRealTimers()
