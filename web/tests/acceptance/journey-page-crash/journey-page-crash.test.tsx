// @vitest-environment jsdom
/**
 * journey-page-crash：旅程頁遇到任何資料形狀都不可整頁白畫面（甲方驗收回饋 2026-11-01，旅程 01M56YZJMN3BCYQ73E9Y941E4C）。
 * 後端依 docs/contracts/journeys.md §1～§2 mock；契約允許的邊界形狀必須正常顯示，
 * 違反契約的形狀（欄位缺漏、型別不對）至少要顯示錯誤狀態（role=alert）且外框仍在，不可讓畫面變空。
 * 假設同 journey-customer-ui/fixtures.tsx：進入點 App、直接以網址 /journeys/<id> 開頁（既有路由）。
 */
import { cleanup, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  backend, bodyText, journeyDetail, journeyVersion, json, problem, resetEnv, start, teardown,
} from '../journey-customer-ui/fixtures'

const REAL_ID = '01M56YZJMN3BCYQ73E9Y941E4C'
const NAME = '壓測旅程'

beforeEach(() => {
  resetEnv()
  // React 會把渲染例外印到 console.error；不影響斷言
  vi.spyOn(console, 'error').mockImplementation(() => undefined)
})
afterEach(() => { cleanup(); teardown(); vi.restoreAllMocks() })

const node = (id: string, type: string, outputs: unknown, name: string | null = id) =>
  ({ id, type, name, config: {}, outputs })

const detail = (over: Record<string, unknown> = {}) =>
  journeyDetail({
    journey_id: REAL_ID, name: NAME, latest_version: 1,
    versions: [{ version: 1, published_at: '2026-10-30T01:00:00Z' }], ...over,
  })
const version = (over: Record<string, unknown> = {}) =>
  journeyVersion(1, 'wait', { journey_id: REAL_ID, name: NAME, ...over })

const nav = () => screen.queryByRole('navigation', { name: '主要導覽' })
const hasTitle = () =>
  screen.queryAllByRole('heading', { level: 1 }).some((h) => (h.textContent ?? '').includes(NAME))

async function open(d: unknown, v: unknown, search = '') {
  backend({
    [`GET /api/v1/journeys/${REAL_ID}`]: () => json(d),
    [`GET /api/v1/journeys/${REAL_ID}/versions/1`]: () => json(v),
  })
  start(`/journeys/${REAL_ID}${search}`)
  // 外框（導覽）必須一直在；頁面要嘛顯示旅程名稱標題，要嘛顯示錯誤狀態，不可是空的
  await waitFor(() => {
    expect(nav()).not.toBeNull()
    expect(hasTitle() || screen.queryAllByRole('alert').length > 0).toBe(true)
  })
  await new Promise((r) => setTimeout(r, 50)) // 讓版本內容請求與渲染走完
  expect(nav()).not.toBeNull()
  expect(document.querySelector('main')?.textContent?.trim().length ?? 0).toBeGreaterThan(0)
}
const showsJourney = () => expect(hasTitle()).toBe(true)
const showsErrorState = () => expect(screen.queryAllByRole('alert').length).toBeGreaterThan(0)
const notBlank = () =>
  expect(screen.queryAllByRole('heading', { level: 1 }).length + screen.queryAllByRole('alert').length).toBeGreaterThan(0)

describe('契約允許的邊界形狀：正常顯示旅程', () => {
  it('壓測樣板的直線旅程（layout null）', async () => {
    await open(detail(), version())
    showsJourney()
    expect(bodyText()).toContain('v1')
  })

  it.each([
    ['layout 為空物件 {}', {}],
    ['layout.nodes 為 null', { nodes: null }],
    ['layout.nodes 為空物件', { nodes: {} }],
    ['layout 座標為 null 或缺漏', { nodes: { start: null, src_1: { x: null, y: 1 }, wait_1: {} } }],
  ])('%s', async (_n, layout) => {
    await open(detail(), version({ layout }))
    showsJourney()
  })

  it('nodes 為空陣列', async () => {
    await open(detail(), version({ nodes: [] }))
    showsJourney()
  })

  it('entry_node 不在 nodes 裡', async () => {
    await open(detail(), version({ entry_node: 'ghost' }))
    showsJourney()
  })

  it('未知節點型別（含 type 為空字串）', async () => {
    await open(detail(), version({
      nodes: [node('start', 'StartNode', { next: 'x' }), node('x', 'FutureNode', { next: 'y' }), node('y', '', {})],
    }))
    showsJourney()
  })

  it('節點 name 為 null', async () => {
    await open(detail(), version({
      nodes: [node('start', 'StartNode', { next: 'e' }, null), node('e', 'EndNode', {}, null)],
    }))
    showsJourney()
  })

  it('迴圈與自連（含連到不存在的節點）', async () => {
    await open(detail(), version({
      nodes: [
        node('start', 'StartNode', { next: 'a' }),
        node('a', 'WaitDelayNode', { next: 'b', loop: 'a', gone: 'nowhere' }),
        node('b', 'ConditionNode', { yes: 'a', no: 'c' }),
        node('c', 'ExitNode', {}),
      ],
    }))
    showsJourney()
  })

  it('節點 id 重複', async () => {
    await open(detail(), version({
      nodes: [node('start', 'StartNode', { next: 'a' }), node('a', 'EndNode', {}), node('a', 'EndNode', {})],
    }))
    showsJourney()
  })

  it('大型旅程（200 個節點的長鏈，契約上限）', async () => {
    const nodes = Array.from({ length: 200 }, (_, i) =>
      node(
        i === 0 ? 'start' : `n${i}`,
        i === 0 ? 'StartNode' : i === 199 ? 'EndNode' : 'WaitDelayNode',
        i === 199 ? {} : { next: `n${i + 1}` },
      ))
    await open(detail(), version({ nodes }))
    showsJourney()
  })

  it('description、published_at 為 null；狀態 ABORTED 且 stopped_by 為 null', async () => {
    await open(
      detail({
        description: null, published_at: null, status: 'ABORTED',
        stopped_at: '2026-10-31T01:00:00Z', stop_reason: '壓測結束', stopped_by: null,
      }),
      version(),
    )
    showsJourney()
  })

  it('latest_version 為 null 且 versions 為空（DRAFT）', async () => {
    await open(detail({ status: 'DRAFT', latest_version: null, versions: [], published_at: null }), version())
    showsJourney()
    expect(bodyText()).toContain('尚未發佈')
  })

  it('多個版本、?version= 指到舊版', async () => {
    const d = detail({
      latest_version: 3,
      versions: [3, 2, 1].map((n) => ({ version: n, published_at: '2026-10-30T01:00:00Z' })),
    })
    backend({
      [`GET /api/v1/journeys/${REAL_ID}`]: () => json(d),
      [`GET /api/v1/journeys/${REAL_ID}/versions/3`]: () => json(version({ version: 3 })),
      [`GET /api/v1/journeys/${REAL_ID}/versions/1`]: () => json(version({ version: 1, layout: { nodes: null } })),
    })
    start(`/journeys/${REAL_ID}?version=1`)
    await screen.findByText(/你正在看 v1/)
    showsJourney()
  })

  it('?version= 指到不存在的版本 → 錯誤狀態而非白畫面', async () => {
    backend({
      [`GET /api/v1/journeys/${REAL_ID}`]: () => json(detail()),
      [`GET /api/v1/journeys/${REAL_ID}/versions/9`]: () => problem(404, 'JOURNEY_NOT_FOUND'),
    })
    start(`/journeys/${REAL_ID}?version=9`)
    await screen.findByText('找不到這個版本。')
    expect(nav()).not.toBeNull()
  })
})

describe('違反契約的形狀：不白畫面（頁面層級錯誤邊界，顯示錯誤狀態、外框仍在）', () => {
  it.each([
    ['節點 outputs 為 null', [node('start', 'StartNode', null), node('e', 'EndNode', {})]],
    ['節點沒有 outputs 欄位', [{ id: 'start', type: 'StartNode', name: 's', config: {} }]],
    ['outputs 的目標為 null', [node('start', 'StartNode', { next: null }), node('e', 'EndNode', {})]],
    ['nodes 內含 null', [node('start', 'StartNode', {}), null]],
  ])('%s', async (_n, nodes) => {
    await open(detail(), version({ nodes }))
    notBlank() // 能顯示就顯示；顯示不了則必須是錯誤狀態
  })

  it('entry_node 為 null、nodes 缺漏', async () => {
    await open(detail(), version({ entry_node: null, nodes: undefined }))
    notBlank()
  })

  it('JourneyDetail.versions 為 null → 錯誤狀態', async () => {
    await open(detail({ versions: null }), version())
    showsErrorState()
  })

  it('JourneyDetail.versions 內含 null → 錯誤狀態', async () => {
    await open(detail({ versions: [null] }), version())
    showsErrorState()
  })

  it('錯誤狀態下外框導覽仍在、有可點的連結', async () => {
    await open(detail({ versions: null }), version())
    expect(nav()!.querySelector('a')).not.toBeNull()
  })
})
