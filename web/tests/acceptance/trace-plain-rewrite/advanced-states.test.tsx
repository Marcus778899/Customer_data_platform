// @vitest-environment jsdom
/** 軌跡頁白話改版：進階資訊、主要畫面禁字（D-079、D-080）、載入／404／錯誤、麵包屑、重新整理、無障礙。 */
import { cleanup, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DETAIL, EID, FORBIDDEN, JID, NOW_EARLY, NOW_LATE, PID, aborted, advanced, advancedText, completed, delivery, failed,
  freezeNow, heading, json, mainText, norm, openTrace, problem, problemWithId, resetEnv, start, teardown,
  unfreeze, waiting, VER, deferred,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); unfreeze() })

describe('進階資訊', () => {
  it('<details> 預設收合，summary 可鍵盤操作（Enter 展開）', async () => {
    freezeNow(NOW_EARLY)
    const user = userEvent.setup()
    openTrace()
    await heading()
    const d = advanced()
    expect(d.open).toBe(false)
    const summary = d.querySelector('summary') as HTMLElement
    expect(summary.textContent).toContain('進階資訊')
    summary.focus()
    await user.keyboard('{Enter}')
    expect(d.open).toBe(true)
  })

  it('含進度編號（可複製）、客戶 ID、旅程 ID 與版本、技術名稱、UTC 原值、as_of、判斷值、系統原因文字、觸發事件、事件屬性', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const t = advancedText()
    for (const s of [
      EID, PID, JID, '棄單挽回', '第 3 版', 'WaitDelayNode', 'wait_1',
      '2026-10-18T02:00:00Z', '2026-10-21T02:00:00Z', 'UTC', 'as_of',
      '判斷時取得的值', 'duration', 'P3D', '系統原因文字', 'cart_item_added', 'CUSTOMER_BEHAVIOR', 'cart_id', 'CART-99201',
    ]) expect(t).toContain(s)
    expect(t).toMatch(/2026-10-21 10:00/) // 本地時間（括號內為 UTC 原值）
    expect(t).toMatch(/UTC\+08:00/)
    expect(within(advanced()).getByRole('button', { name: /複製/ })).toBeTruthy()
  })

  it('系統原因文字：原文顯示，其中的 ISO UTC 時間轉成本地時間', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    expect(advancedText()).toMatch(/等待 P3D，預計 .*2026-10-21 10:00/)
  })

  it('遮罩欄位：列出 masked_paths，標「已遮罩」且無複製；未列入的 *** 原樣', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({
      trigger_snapshot: {
        ...waiting().trigger_snapshot,
        properties: { email: 'a***@example.com', note: '***' },
        profile_snapshot: { name: '陳＊＊' },
      },
      masked_paths: ['trigger_snapshot.properties.email', 'trigger_snapshot.profile_snapshot.name'],
    }))
    await heading()
    const t = advancedText()
    expect(t).toContain('trigger_snapshot.properties.email')
    expect(t).toContain('無法解除')
    const dd = (k: string) => [...advanced().querySelectorAll('dt')].find((e) => e.textContent === k)!.nextElementSibling!
    expect(dd('email').textContent).toContain('已遮罩')
    expect(dd('email').querySelector('button')).toBeNull()
    expect(dd('note').textContent).not.toContain('已遮罩')
    expect(mainText()).toContain('保護個資')
  })
})

describe('主要畫面不出現技術字（D-079、D-080）', () => {
  const states: [string, Date, () => unknown][] = [
    ['等待中', NOW_EARLY, () => waiting()],
    ['走完待送', NOW_LATE, () => completed({ delivery_history: [delivery(1, { status: 'PENDING', delivery_id: null })] })],
    ['走完已送出', NOW_LATE, () => completed()],
    ['發生錯誤', NOW_LATE, () => failed()],
    ['已中止', NOW_LATE, () => aborted()],
  ]
  for (const [name, now, make] of states) {
    it(`${name}：主要區（扣掉 details）沒有 Enrollment、節點型別名、UTC、ID、ISO 期間、管道代碼`, async () => {
      freezeNow(now)
      openTrace({}, make())
      await heading()
      const text = mainText()
      for (const [label, re] of FORBIDDEN) expect({ label, hit: re.exec(text)?.[0] ?? null }).toEqual({ label, hit: null })
    })
  }

  it('取不到旅程定義時同樣乾淨', async () => {
    freezeNow(NOW_EARLY)
    openTrace({ [`GET ${VER}`]: () => problem(404, 'JOURNEY_NOT_FOUND') })
    await heading()
    const text = mainText()
    for (const [label, re] of FORBIDDEN) expect({ label, hit: re.exec(text)?.[0] ?? null }).toEqual({ label, hit: null })
  })

  it('網址仍含進度編號（深層連結）', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    expect(window.location.pathname).toBe(`/enrollments/${EID}`)
  })
})

describe('載入、404、錯誤', () => {
  it('載入中：h1「載入中…」、role=status aria-busy 的骨架', async () => {
    const d = deferred<Response>()
    openTrace({ [`GET ${DETAIL}`]: () => d.promise })
    await screen.findByRole('heading', { level: 1, name: '載入中…' })
    const busy = screen.getAllByRole('status').find((s) => s.getAttribute('aria-busy') === 'true')
    expect(busy).toBeTruthy()
    d.resolve(json(waiting()))
    await heading()
  })

  it('404：「找不到這筆進度」、錯誤代碼（request_id）、「回到客戶搜尋」連到客戶頁；沒有 Enrollment 字樣', async () => {
    openTrace({ [`GET ${DETAIL}`]: () => problemWithId(404, 'ENROLLMENT_NOT_FOUND', 'req-9f3a21c4') })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('找不到這筆進度')
    expect(alert.textContent).toContain('網址可能有誤')
    expect(alert.textContent).toContain('req-9f3a21c4')
    expect(alert.textContent).not.toContain('Enrollment')
    expect(within(alert).getByRole('link', { name: '回到客戶搜尋' }).getAttribute('href')).toBe('/customers')
  })

  it('5xx：role=alert、request_id、重試重打同一請求；403 沒有重試', async () => {
    let n = 0
    openTrace({ [`GET ${DETAIL}`]: () => (++n === 1 ? problemWithId(503, 'SERVICE_UNAVAILABLE', 'REQ-503') : json(waiting())) })
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('REQ-503')
    await userEvent.click(within(alert).getByRole('button', { name: '重試' }))
    await heading()
    expect(n).toBe(2)
    cleanup()
    openTrace({ [`GET ${DETAIL}`]: () => problem(403, 'FORBIDDEN') })
    const a403 = await screen.findByRole('alert')
    expect(a403.textContent).toContain('沒有權限')
    expect(within(a403).queryAllByRole('button')).toHaveLength(0)
  })
})

describe('麵包屑、重新整理、無障礙', () => {
  it('麵包屑：客戶／客戶姓名或遮罩／旅程進度；最後一段純文字 aria-current=page，前面是連結', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({
      trigger_snapshot: { ...waiting().trigger_snapshot, profile_snapshot: { name: '王小明' } },
    }))
    await heading()
    const nav = screen.getByRole('navigation', { name: '麵包屑' })
    const li = [...nav.querySelectorAll('ol > li')]
    expect(li.map(norm)).toEqual(['客戶', '王小明', '旅程進度'])
    expect(li[0].querySelector('a')!.getAttribute('href')).toBe('/customers')
    expect(li[1].querySelector('a')!.getAttribute('href')).toBe(`/customers/${PID}`)
    expect(li[2].getAttribute('aria-current')).toBe('page')
    expect(li[2].querySelector('a')).toBeNull()
    expect(nav.textContent).not.toContain(PID)
  })

  it('麵包屑的客戶段：姓名被遮罩時顯示遮罩值；無姓名時不顯示 ID', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting())
    await heading()
    const nav = screen.getByRole('navigation', { name: '麵包屑' })
    expect(nav.textContent).not.toContain(PID)
    expect(nav.textContent).not.toMatch(/01JCX7K2/)
    expect(nav.querySelector('li:last-child')!.textContent).toBe('旅程進度')
    // trace-customer-name：無姓名也無識別碼時，中段是「未具名客戶」（不再是「這位客戶」）
    await waitFor(() => expect([...nav.querySelectorAll('ol > li')].map(norm)[1]).toBe('未具名客戶'))
  })

  it('換頁後 h1 取得焦點', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    const h = await heading()
    await waitFor(() => expect(document.activeElement).toBe(h))
  })

  it('重新整理：重打同一請求、更新「更新」時間、按鈕仍可用；沒有 UTC 勾選', async () => {
    freezeNow(NOW_EARLY)
    let n = 0
    const api = openTrace({
      [`GET ${DETAIL}`]: () => { n++; return json(waiting({ as_of: n === 1 ? '2026-10-18T03:00:00Z' : '2026-10-18T03:30:00Z' })) },
    })
    const user = userEvent.setup()
    await heading()
    expect(screen.queryByLabelText(/UTC/)).toBeNull()
    await user.click(screen.getByRole('button', { name: '重新整理' }))
    await waitFor(() => expect(n).toBe(2))
    expect(api.calls.filter((c) => c.path === DETAIL)).toHaveLength(2)
    expect(mainText()).toMatch(/更新/)
    expect(screen.getByRole('button', { name: '重新整理' })).toBeTruthy()
  })

  it('唯讀：沒有任何寫入操作，也不發出非 GET 請求（auth 除外）', async () => {
    freezeNow(NOW_EARLY)
    const api = openTrace()
    await heading()
    expect(screen.queryByRole('button', { name: /暫停|恢復|重送|重新發送|編輯|修改|刪除|取消發送|終止|pause|resend|edit|delete/i })).toBeNull()
    expect(api.calls.filter((c) => c.method !== 'GET' && !c.path.includes('/auth/'))).toEqual([])
  })

  it('狀態不只靠顏色：時間軸每步有文字狀態字；摘要卡 role=status', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    await screen.findByRole('heading', { name: '走過的步驟' })
    const lis = [...document.querySelectorAll('main ol > li')].filter((l) => !l.closest('nav'))
    expect(lis.length).toBeGreaterThan(0)
    for (const li of lis) {
      expect(norm(li)).toMatch(/已完成|目前在這裡|接下來|發生問題|已中止/)
    }
    expect(screen.getAllByRole('status').some((s) => s.textContent?.includes('這位客戶現在在哪'))).toBe(true)
  })
})

describe('從客戶頁進入仍可用（整合）', () => {
  it('深層連結 401 → 登入 → 回原軌跡頁並顯示白話畫面', async () => {
    freezeNow(NOW_EARLY)
    let loggedIn = false
    const { mockApi, AMY } = await import('../shell/helpers')
    mockApi({
      me: null,
      routes: {
        'GET /api/v1/auth/me': () => (loggedIn ? json(AMY) : problem(401, 'UNAUTHENTICATED')),
        'POST /api/v1/auth/login': () => { loggedIn = true; return new Response(null, { status: 204 }) },
        [`GET ${DETAIL}`]: () => (loggedIn ? json(waiting()) : problem(401, 'UNAUTHENTICATED')),
      },
    })
    const user = userEvent.setup()
    start(`/enrollments/${EID}`)
    await user.type(await screen.findByLabelText('Email'), 'amy.chen@example.com')
    await user.type(screen.getByLabelText('密碼'), 'pw')
    await user.click(screen.getByRole('button', { name: '登入' }))
    await heading()
    expect(window.location.pathname).toBe(`/enrollments/${EID}`)
  })
})
