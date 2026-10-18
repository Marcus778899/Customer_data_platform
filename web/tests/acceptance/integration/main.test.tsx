// @vitest-environment jsdom
/**
 * INT-01 驗收測試（qa 撰寫，worker 不可改）：main.tsx 啟動後不安裝 mock。
 * 獨立成一個檔案，因為 main.tsx 會把 App 掛到 #root 且無法卸載，避免干擾其他測試。
 */
import { screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

describe('main.tsx 不載入 mock', () => {
  it.each([undefined, 'true'])('VITE_API_MOCK=%s：全域 fetch 不被取代，打真實 /api/v1/auth/me 並帶 credentials', async (flag) => {
    vi.resetModules()
    if (flag) vi.stubEnv('VITE_API_MOCK', flag)
    const fn = vi.fn(async () =>
      new Response(JSON.stringify({ type: 'about:blank', title: 'x', status: 401, code: 'UNAUTHENTICATED', detail: 'd', request_id: 'R' }), {
        status: 401,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    )
    vi.stubGlobal('fetch', fn)
    document.body.innerHTML = '<div id="root"></div>'
    window.history.pushState({}, '', '/')
    await import('../../../src/main')
    await screen.findByLabelText('帳號')
    expect(globalThis.fetch).toBe(fn) // mock 若被安裝會換掉 fetch
    const [path, init] = fn.mock.calls[0] as unknown as [string, RequestInit | undefined]
    expect(path).toBe('/api/v1/auth/me')
    expect(['same-origin', 'include']).toContain(init?.credentials)
  })
})
