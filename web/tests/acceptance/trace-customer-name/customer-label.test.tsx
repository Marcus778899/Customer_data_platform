// @vitest-environment jsdom
/**
 * 軌跡頁客戶標示（trace-customer-name；甲方回饋 2026-11-02；D-014、D-021、D-080）。
 * 取得順序：1. 軌跡 profile_snapshot 姓名；2. GET /profiles/{id} 的 attributes.name（未遮罩）；
 *           3. 第一個識別碼（照後端遮罩值）；4. 「未具名客戶」。麵包屑中段與副標用同一標示，中段連到客戶頁。
 * 共用工具沿用 trace-plain-rewrite/fixtures（worker 不可改）。
 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DETAIL, NOW_EARLY, PID, deferred, freezeNow, heading, json, mainText, norm, openTrace, problem, resetEnv, teardown,
  unfreeze, waiting,
} from '../trace-plain-rewrite/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); unfreeze() })

const PROFILE = `/api/v1/profiles/${PID}`

function profile(over: Record<string, unknown> = {}) {
  return {
    profile_id: PID,
    identifiers: [],
    created_at: '2026-05-03T03:20:00Z', updated_at: '2026-11-01T08:00:00Z',
    attributes: {}, masked_paths: [], as_of: '2026-11-01T08:05:12Z', ...over,
  }
}
const withSnapshot = (profile_snapshot: Record<string, unknown>, over: Record<string, unknown> = {}) =>
  waiting({ trigger_snapshot: { ...waiting().trigger_snapshot, profile_snapshot }, ...over })

const crumbs = () => [...screen.getByRole('navigation', { name: '麵包屑' }).querySelectorAll('ol > li')]
/** 等麵包屑中段變成指定文字（客戶資料是額外取得的，可能晚於軌跡出現） */
async function crumbIs(text: string) {
  await waitFor(() => expect(norm(crumbs()[1])).toBe(text))
}
const subtitle = () => mainText()

describe('取得順序', () => {
  it('1. 軌跡 profile_snapshot 有姓名：用它，麵包屑與副標一致，連到客戶頁', async () => {
    freezeNow(NOW_EARLY)
    openTrace(
      { [`GET ${PROFILE}`]: () => json(profile({ attributes: { name: '別人' } })) },
      withSnapshot({ name: '王小明' }),
    )
    await heading()
    await crumbIs('王小明')
    expect(subtitle()).toContain('客戶：王小明')
    expect(subtitle()).not.toContain('別人')
    expect(crumbs()[1].querySelector('a')!.getAttribute('href')).toBe(`/customers/${PID}`)
  })

  it('2. 軌跡沒姓名、客戶資料 attributes.name 未遮罩：用該姓名', async () => {
    freezeNow(NOW_EARLY)
    const api = openTrace({
      [`GET ${PROFILE}`]: () => json(profile({
        attributes: { name: '林小華' },
        identifiers: [{ type: 'EMAIL', value: 'l***@example.com', masked: true }],
        masked_paths: ['identifiers[0].value'],
      })),
    })
    await heading()
    await crumbIs('林小華')
    expect(subtitle()).toContain('客戶：林小華')
    expect(subtitle()).not.toContain('l***@example.com')
    expect(api.calls.some((c) => c.method === 'GET' && c.path === PROFILE)).toBe(true)
    expect(crumbs()[1].querySelector('a')!.getAttribute('href')).toBe(`/customers/${PID}`)
  })

  it('3. 姓名被遮罩：不用姓名屬性，改用第一個識別碼（後端遮罩值）', async () => {
    freezeNow(NOW_EARLY)
    openTrace({
      [`GET ${PROFILE}`]: () => json(profile({
        attributes: { name: '林＊＊' },
        identifiers: [
          { type: 'EMAIL', value: 'c***@example.com', masked: true },
          { type: 'MEMBER_ID', value: 'M-20418', masked: false },
        ],
        masked_paths: ['attributes.name', 'identifiers[0].value'],
      })),
    })
    await heading()
    await crumbIs('c***@example.com')
    expect(subtitle()).toContain('客戶：c***@example.com')
    expect(subtitle()).not.toContain('林＊＊')
    expect(subtitle()).not.toContain('M-20418')
  })

  it('3. 沒有 name 屬性：第一個識別碼，即使不是 email（例會員編號、明文）', async () => {
    freezeNow(NOW_EARLY)
    openTrace({
      [`GET ${PROFILE}`]: () => json(profile({
        identifiers: [{ type: 'MEMBER_ID', value: 'M-20418', masked: false }],
      })),
    })
    await heading()
    await crumbIs('M-20418')
    expect(subtitle()).toContain('客戶：M-20418')
  })

  it('3. 手機識別碼：照遮罩值顯示，不顯示明文', async () => {
    freezeNow(NOW_EARLY)
    openTrace({
      [`GET ${PROFILE}`]: () => json(profile({
        identifiers: [{ type: 'PHONE', value: '+8869*****678', masked: true }],
        masked_paths: ['identifiers[0].value'],
      })),
    })
    await heading()
    await crumbIs('+8869*****678')
    expect(subtitle()).toContain('客戶：+8869*****678')
    expect(subtitle()).not.toContain('0912345678')
  })

  it('4. 姓名與識別碼都沒有：麵包屑與副標都是「未具名客戶」，不再出現舊字樣', async () => {
    freezeNow(NOW_EARLY)
    openTrace({ [`GET ${PROFILE}`]: () => json(profile()) })
    await heading()
    await crumbIs('未具名客戶')
    expect(subtitle()).toContain('客戶：未具名客戶')
    expect(subtitle()).not.toContain('未提供姓名')
    expect(norm(crumbs()[1])).not.toBe('這位客戶')
    expect(crumbs()[1].querySelector('a')!.getAttribute('href')).toBe(`/customers/${PID}`)
  })
})

describe('/profiles 取得失敗不影響軌跡', () => {
  const cases: [string, () => Response | Promise<Response>][] = [
    ['404 PROFILE_NOT_FOUND', () => problem(404, 'PROFILE_NOT_FOUND')],
    ['500', () => problem(500, 'INTERNAL')],
    ['連線失敗', () => Promise.reject(new TypeError('Failed to fetch'))],
  ]
  for (const [name, h] of cases) {
    it(`${name}：頁面照常顯示，退回「未具名客戶」，沒有錯誤框蓋掉軌跡`, async () => {
      freezeNow(NOW_EARLY)
      openTrace({ [`GET ${PROFILE}`]: h })
      const hd = await heading()
      expect(hd.textContent).toMatch(/棄單挽回/)
      await crumbIs('未具名客戶')
      expect(subtitle()).toContain('客戶：未具名客戶')
      expect(screen.getByRole('heading', { name: '走過的步驟' })).toBeTruthy()
      expect(screen.queryByText('無法載入旅程進度')).toBeNull()
      expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('這位客戶現在在哪'))).toBe(true)
    })
  }

  it('客戶資料還沒回來時，軌跡內容不必等它（先顯示、之後補上標示）', async () => {
    freezeNow(NOW_EARLY)
    const d = deferred<Response>()
    openTrace({ [`GET ${PROFILE}`]: () => d.promise })
    await heading()
    expect(screen.getByRole('heading', { name: '走過的步驟' })).toBeTruthy()
    expect(crumbs()).toHaveLength(3)
    expect(norm(crumbs()[2])).toBe('旅程進度')
    d.resolve(json(profile({ identifiers: [{ type: 'EMAIL', value: 'c***@example.com', masked: true }] })))
    await crumbIs('c***@example.com')
  })

  it('軌跡本身 404（進度不存在）時不取客戶資料也不出錯：維持「找不到這筆進度」', async () => {
    freezeNow(NOW_EARLY)
    const api = openTrace({ [`GET ${DETAIL}`]: () => problem(404, 'ENROLLMENT_NOT_FOUND') })
    await screen.findByRole('heading', { name: '找不到這筆進度' })
    expect(api.calls.filter((c) => c.path === PROFILE)).toHaveLength(0)
  })
})

describe('隱私（D-014、D-021、D-080）', () => {
  it('主要畫面（含麵包屑）不出現客戶 ID；只用唯讀 GET 取客戶資料', async () => {
    freezeNow(NOW_EARLY)
    const api = openTrace({
      [`GET ${PROFILE}`]: () => json(profile({ identifiers: [{ type: 'EMAIL', value: 'c***@example.com', masked: true }] })),
    })
    await heading()
    await crumbIs('c***@example.com')
    expect(mainText()).not.toContain(PID)
    expect(norm(screen.getByRole('navigation', { name: '麵包屑' }))).not.toContain(PID)
    expect(api.calls.filter((c) => c.path.startsWith('/api/v1/profiles')).every((c) => c.method === 'GET')).toBe(true)
    expect(api.calls.filter((c) => c.path === PROFILE).length).toBeGreaterThan(0)
  })

  it('遮罩值照原樣顯示：不會被還原或補全，也沒有複製按鈕', async () => {
    freezeNow(NOW_EARLY)
    openTrace({
      [`GET ${PROFILE}`]: () => json(profile({
        identifiers: [{ type: 'EMAIL', value: 'c***@example.com', masked: true }],
        masked_paths: ['identifiers[0].value'],
      })),
    })
    await heading()
    await crumbIs('c***@example.com')
    expect(mainText()).not.toMatch(/carol|c[a-z]+@example\.com/)
    // 範圍：麵包屑與主要區（不含進階資訊 details，其進度編號依規格可複製）
    expect(within(screen.getByRole('navigation', { name: '麵包屑' })).queryByRole('button', { name: /複製/ })).toBeNull()
    const outsideDetails = screen
      .queryAllByRole('button', { name: /複製/, hidden: true })
      .filter((b) => !b.closest('details'))
    expect(outsideDetails).toHaveLength(0)
  })
})
