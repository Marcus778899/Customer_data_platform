// @vitest-environment jsdom
/**
 * journey-builder-integration（前端）：以有狀態的假後端（依 journeys.md §1、§3、auth.md §1.1 實作規則）串起
 * 「編輯者畫布建旅程 → 送審 → 管理員（另一人）核准上線」，驗證前端與契約的對接細節：
 * 路徑、請求內容、CSRF 標頭、revision 傳遞、不可核准自己送的。真後端端到端見 api 端的 journey-builder-integration。
 *
 * 對 worker 的假設：沿用 journey-canvas-ui／journey-review-ui 的畫面規則（新建草稿 `/journeys/new`、
 * 「設定進入事件」、「送審」對話框、旅程頁「核准並發佈」）；沒有任何 mock 路徑（VITE_API_MOCK 等）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  AMY, backend, bodyText, cleanup, journeyDetail, json, problem, resetEnv, screen, start, teardown, user, userEvent,
  waitFor,
} from '../../journey-canvas-ui/fixtures'
import type { Call, Def } from '../../journey-canvas-ui/fixtures'
import { dialog, expectAbsent, mustAction, REF_AMY, ref } from '../../journey-review-ui/fixtures'
import { cookie } from '../../shell/helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const JID = '01JD9BUILDER0000000000001'
const RID = '01JD9REVIEW00000000000001'
const API = `/api/v1/journeys/${JID}`
const ADMIN2 = user({ user_id: '01JD0USER0000000000000009', email: 'chen@example.com', display_name: '陳大文', role: 'ADMIN' })

interface World {
  name: string
  revision: number
  definition: Def | null
  review: Record<string, unknown> | null
  versions: number[]
  submittedBy: typeof REF_AMY | null
}

function world() {
  const w: World = { name: '', revision: 0, definition: null, review: null, versions: [], submittedBy: null }
  const csrfOk = (c: Call) => !!c.headers['x-xsrf-token'] && c.headers['x-xsrf-token'] === cookie('XSRF-TOKEN')
  const guard = (fn: (c: Call) => Response) => (c: Call) => (csrfOk(c) ? fn(c) : problem(403, 'CSRF_INVALID'))
  const briefOf = (r: Record<string, unknown> | null) => (r ? { ...r } : null)
  const detail = () =>
    journeyDetail({
      journey_id: JID, name: w.name, status: w.versions.length ? 'ACTIVE' : 'DRAFT',
      latest_version: w.versions.at(-1) ?? null, published_at: w.versions.length ? '2026-11-18T01:00:00Z' : null,
      has_draft: !!w.definition, review_status: w.review ? (w.review.status as string) : null, latest_review: briefOf(w.review),
      versions: w.versions.map((v) => ({ version: v, published_at: '2026-11-18T01:00:00Z' })).reverse(),
    })
  const draft = () => ({
    journey_id: JID, revision: w.revision, definition: w.definition, layout: null, updated_at: '2026-11-18T01:00:00Z',
    updated_by: REF_AMY, latest_review: w.review,
  })
  const api = backend({
    'POST /api/v1/journeys/drafts': guard((c) => {
      w.name = (c.body as { name: string }).name
      w.revision = 1
      w.definition = { name: w.name, description: null, settings: {}, entry_node: 'start', nodes: [{ id: 'start', type: 'StartNode', name: null, config: {}, outputs: {} }] }
      return json({ journey_id: JID, status: 'DRAFT', revision: 1 }, 201)
    }),
    [`GET ${API}/draft`]: () => (w.definition ? json(draft()) : problem(404, 'DRAFT_NOT_FOUND')),
    [`PUT ${API}/draft`]: guard((c) => {
      const b = c.body as { revision: number; definition: Def }
      if (w.review?.status === 'PENDING') return problem(409, 'DRAFT_IN_REVIEW')
      if (b.revision !== w.revision) return problem(409, 'DRAFT_CONFLICT')
      w.revision += 1
      w.definition = b.definition
      return json(draft())
    }),
    [`POST ${API}/draft/validate`]: guard(() => json({ valid: true, errors: [], warnings: [] })),
    'GET /api/v1/journeys/field-keys': () => json({ profile_attributes: [], event_properties: [], as_of: '2026-11-18T01:00:00Z' }),
    [`GET ${API}`]: () => json(detail()),
    [`GET ${API}/reviews`]: () => json({ items: w.review ? [w.review] : [], next_cursor: null }),
    [`POST ${API}/draft/submit`]: guard((c) => {
      const b = c.body as { revision: number }
      if (b.revision !== w.revision) return problem(409, 'DRAFT_CONFLICT')
      w.submittedBy = ref(api.me!.user_id, api.me!.display_name)
      w.review = {
        review_id: RID, journey_id: JID, action: 'PUBLISH', status: 'PENDING', revision: w.revision, base_version: null, note: null,
        submitted_by: w.submittedBy, submitted_at: '2026-11-18T01:00:00Z', decided_by: null, decided_at: null, reason: null, version: null,
      }
      return json(w.review, 201)
    }),
    [`POST ${API}/reviews/${RID}/approve`]: guard(() => {
      if (w.submittedBy?.user_id === api.me!.user_id) return problem(403, 'SELF_APPROVAL_FORBIDDEN')
      w.versions.push(1)
      w.definition = null
      w.review = { ...w.review!, status: 'APPROVED', decided_by: ref(api.me!.user_id, api.me!.display_name), version: 1 }
      return json(w.review)
    }),
  }, AMY)
  return { w, api }
}

describe('畫布建旅程 → 送審 → 他人核准上線', () => {
  it('編輯者建草稿、設進入事件、送審；另一位管理員核准後旅程為 v1；請求都帶 CSRF 與 revision', async () => {
    const { w, api } = world()

    // 1) 編輯者：建立草稿
    start('/journeys/new')
    await userEvent.type(await screen.findByLabelText('旅程名稱'), '棄單挽回')
    await userEvent.click(screen.getByRole('button', { name: '建立草稿' }))
    await screen.findByRole('complementary', { name: '節點庫' })
    expect(window.location.pathname).toBe(`/journeys/${JID}/edit`)

    // 2) 設進入事件 → 自動儲存（PUT 帶目前 revision）
    await userEvent.click(await screen.findByRole('button', { name: '設定進入事件' }))
    await userEvent.type(await screen.findByRole('textbox', { name: /事件/ }), 'cart_item_added')
    await waitFor(() => expect(w.definition?.nodes.some((n) => n.type === 'EventSourceNode')).toBe(true), { timeout: 8000 })
    const put = api.calls.filter((c) => c.method === 'PUT' && c.path === `${API}/draft`)
    expect(put.length).toBeGreaterThan(0)
    expect((put[0].body as { revision: number }).revision).toBe(1)

    // 3) 送審：用最新 revision；請求不是 mock、帶 CSRF
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    const d = await dialog(/送審？/)
    const submitBtn = [...d.querySelectorAll('button')].find((b) => b.textContent === '送審')!
    await userEvent.click(submitBtn)
    await waitFor(() => expect(w.review?.status).toBe('PENDING'))
    const submit = api.calls.find((c) => c.method === 'POST' && c.path === `${API}/draft/submit`)!
    expect((submit.body as { revision: number }).revision).toBe(w.revision)
    expect(submit.headers['x-xsrf-token']).toBeTruthy()
    await waitFor(() => expect(bodyText()).toContain('草稿已鎖定，不能修改'))
    expect(w.versions).toEqual([])

    // 4) 送審者自己（即使是管理員）看不到可用的核准（不可核准自己送的）
    api.me = { ...AMY, role: 'ADMIN' }
    cleanup()
    start(API.replace('/api/v1', ''))
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    const own = await (async () => { try { return await mustAction('核准並發佈') } catch { return null } })()
    if (own) expect(own.getAttribute('aria-disabled')).toBe('true')
    expect(api.calls.some((c) => c.path.endsWith('/approve'))).toBe(false)

    // 5) 另一位管理員核准 → 旅程 v1 上線
    api.me = ADMIN2
    cleanup()
    start(API.replace('/api/v1', ''))
    await screen.findByRole('heading', { level: 1, name: /棄單挽回/ })
    await userEvent.click(await mustAction('核准並發佈'))
    const ok = await dialog(/核准並發佈/)
    await userEvent.click([...ok.querySelectorAll('button')].find((b) => b.textContent === '核准並發佈')!)
    await waitFor(() => expect(w.versions).toEqual([1]))
    const approve = api.calls.find((c) => c.method === 'POST' && c.path === `${API}/reviews/${RID}/approve`)!
    expect(approve.headers['x-xsrf-token']).toBeTruthy()
    await waitFor(() => expect(bodyText()).toContain('最新版本 v1'))
    await expectAbsent('核准並發佈')
  })
})
