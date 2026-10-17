// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { mockHandle } from '../../src/mock/mockApi'

const PROFILE = '01JCXYZPROFILE00000000001'
const login = () => mockHandle('POST', '/api/v1/auth/login', JSON.stringify({ username: 'support01', password: 'x' }))

describe('mock API 依契約回應', () => {
  beforeEach(() => sessionStorage.clear())

  it('未登入一律 401 UNAUTHENTICATED，帶 request_id', async () => {
    const res = mockHandle('GET', '/api/v1/enrollments?q=x', undefined)
    expect(res.status).toBe(401)
    const body = await res.json()
    expect(body.code).toBe('UNAUTHENTICATED')
    expect(body.request_id).toBeTruthy()
  })

  it('登入 204；缺欄位 400；帳號錯 401 INVALID_CREDENTIALS', () => {
    expect(mockHandle('POST', '/api/v1/auth/login', '{}').status).toBe(400)
    expect(mockHandle('POST', '/api/v1/auth/login', JSON.stringify({ username: 'x', password: 'y' })).status).toBe(401)
    expect(login().status).toBe(204)
    expect(mockHandle('GET', '/api/v1/auth/me', undefined).status).toBe(200)
  })

  it('查詢依 q 與 status 過濾；查無為 200 空陣列', async () => {
    login()
    const all = await mockHandle('GET', `/api/v1/enrollments?q=${PROFILE}`, undefined).json()
    expect(all.items).toHaveLength(3)
    const waiting = await mockHandle('GET', `/api/v1/enrollments?q=${PROFILE}&status=WAITING`, undefined).json()
    expect(waiting.items.map((i: { status: string }) => i.status)).toEqual(['WAITING'])
    const none = await mockHandle('GET', '/api/v1/enrollments?q=UNKNOWN', undefined)
    expect(none.status).toBe(200)
    expect((await none.json()).items).toEqual([])
  })

  it('詳情 404 與發送歷史分頁', async () => {
    login()
    expect(mockHandle('GET', '/api/v1/enrollments/NOPE', undefined).status).toBe(404)
    const d = await mockHandle('GET', '/api/v1/enrollments/01JCXYZENROLL000000000003', undefined).json()
    expect(d.delivery_history).toHaveLength(50)
    const page = await mockHandle(
      'GET',
      `/api/v1/enrollments/01JCXYZENROLL000000000003/deliveries?cursor=${d.delivery_history_next_cursor}`,
      undefined,
    ).json()
    expect(page.items).toHaveLength(10)
    expect(page.next_cursor).toBeNull()
  })
})
