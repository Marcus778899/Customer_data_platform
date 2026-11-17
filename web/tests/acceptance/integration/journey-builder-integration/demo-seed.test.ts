// @vitest-environment node
/**
 * journey-builder-integration：`make demo-seed`（tools/demo-seed/seed.py）建出縮減版場景一與迴圈旅程，
 * 且都走「草稿 → editor 送審 → 另一位 admin 核准」（不放寬 journeys.md §3.1）。
 * 以本機假後端（依 journeys.md §3、auth.md §1.1／§2／§4 的規則）接 seed.py 的 HTTP 請求，驗收它送出的內容。
 * 假後端的 session cookie 帶 Secure、寫入請求要 XSRF 標頭，seed 必須手動帶 Cookie 與 X-XSRF-TOKEN。
 * 需要 python3（與 make demo-seed 相同）。
 */
import { spawn } from 'node:child_process'
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'
import { fileURLToPath } from 'node:url'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

const SEED = fileURLToPath(new URL('../../../../../tools/demo-seed/seed.py', import.meta.url))
const ADMIN_KEY = 'test-admin-key-00000000000000000000000'
const INGEST_KEY = 'test-ingest-key-0000000000000000000000'
const PASSWORD = 'demo-password-123456'

interface Node { id: string; type: string; name?: string | null; config: Record<string, unknown>; outputs: Record<string, unknown> }
interface Def { name: string; settings: Record<string, unknown>; entry_node: string; nodes: Node[] }
interface Rec { method: string; path: string; actor: string; body: unknown }

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const ulid = () => Array.from({ length: 26 }, () => CROCKFORD[Math.floor(Math.random() * 32)]).join('')

function fakeBackend() {
  const users = new Map<string, { id: string; email: string; role: string; name: string; password: string }>()
  const sessions = new Map<string, { email: string; token: string }>()
  const drafts = new Map<string, { name: string; revision: number; definition: Def | null; submittedBy: string | null; reviewId: string | null }>()
  const published = new Set<string>()
  const log: Rec[] = []
  const problems: string[] = []
  let seq = 0

  const send = (res: ServerResponse, status: number, body?: unknown, headers: Record<string, string | string[]> = {}) => {
    res.writeHead(status, { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers })
    res.end(body === undefined ? undefined : JSON.stringify(body))
  }
  const fail = (res: ServerResponse, status: number, code: string, why: string) => {
    problems.push(`${status} ${code}: ${why}`)
    send(res, status, { type: 'about:blank', title: code, status, code, detail: why, request_id: 'REQ-' + code })
  }
  const cookies = (req: IncomingMessage) =>
    Object.fromEntries((req.headers.cookie ?? '').split(';').map((c) => c.trim()).filter(Boolean).map((c) => [c.slice(0, c.indexOf('=')), c.slice(c.indexOf('=') + 1)]))

  async function handle(req: IncomingMessage, res: ServerResponse) {
    const url = new URL(req.url ?? '/', 'http://x')
    const method = req.method ?? 'GET'
    let raw = ''
    for await (const chunk of req) raw += chunk
    const body = raw ? JSON.parse(raw) : undefined
    const path = url.pathname
    const auth = req.headers.authorization
    const ck = cookies(req)
    const sess = ck.CDP_SESSION ? sessions.get(ck.CDP_SESSION) : undefined
    const me = sess ? users.get(sess.email) : undefined
    const actor = me ? me.email : auth === 'Bearer ' + ADMIN_KEY ? 'admin-key' : auth === 'Bearer ' + INGEST_KEY ? 'ingest-key' : 'anonymous'
    log.push({ method, path, actor, body })

    // 登入（Secure cookie：假後端只認 Cookie 標頭，client 要手動帶）
    if (method === 'POST' && path === '/api/v1/auth/login') {
      const u = users.get(String(body?.email).toLowerCase())
      if (!u || u.password !== body.password) return fail(res, 401, 'INVALID_CREDENTIALS', 'bad login')
      const sid = 'sid-' + ++seq
      const token = 'tok-' + ++seq
      sessions.set(sid, { email: u.email, token })
      return send(res, 204, undefined, { 'Set-Cookie': [`CDP_SESSION=${sid}; Path=/api; HttpOnly; Secure; SameSite=Strict`, `XSRF-TOKEN=${token}; Path=/; Secure; SameSite=Strict`] })
    }
    if (method === 'GET' && path === '/api/v1/auth/me') {
      if (!sess || !me) return fail(res, 401, 'UNAUTHENTICATED', 'no session')
      sess.token = 'tok-' + ++seq
      return send(res, 200, { user_id: me.id, email: me.email, display_name: me.name, role: me.role }, { 'Set-Cookie': `XSRF-TOKEN=${sess.token}; Path=/; Secure; SameSite=Strict` })
    }

    // 使用者管理（管理金鑰）
    if (path === '/api/v1/users') {
      if (actor !== 'admin-key') return fail(res, 401, 'UNAUTHENTICATED', 'users needs admin key')
      if (method === 'POST') {
        const email = String(body.email).toLowerCase()
        if (users.has(email)) return fail(res, 409, 'EMAIL_EXISTS', email)
        const u = { id: ulid(), email, role: body.role, name: body.display_name, password: body.password }
        users.set(email, u)
        return send(res, 201, { user_id: u.id, email, display_name: u.name, role: u.role, status: 'ACTIVE' })
      }
      return send(res, 200, { items: [...users.values()].map((u) => ({ user_id: u.id, email: u.email, role: u.role, display_name: u.name, status: 'ACTIVE' })), next_cursor: null })
    }
    const patch = path.match(/^\/api\/v1\/users\/([^/]+)$/)
    if (patch && method === 'PATCH') {
      const u = [...users.values()].find((x) => x.id === patch[1])
      if (!u) return fail(res, 404, 'USER_NOT_FOUND', patch[1])
      u.password = body.password ?? u.password
      return send(res, 200, { user_id: u.id, email: u.email, display_name: u.name, role: u.role, status: 'ACTIVE' })
    }

    // 客戶、事件、進度（供 seed 的其餘部分）
    if (method === 'PUT' && path.startsWith('/api/v1/profiles/')) return send(res, 200, { profile_id: path.split('/').pop() })
    if (method === 'POST' && path === '/api/v1/events') return send(res, 202, { status: 'ACCEPTED' })
    if (method === 'GET' && path === '/api/v1/enrollments') return send(res, 200, { items: [], next_cursor: null })

    // 旅程：草稿、審核
    if (path.startsWith('/api/v1/journeys')) {
      if (method === 'POST' && path === '/api/v1/journeys') {
        problems.push('POST /api/v1/journeys（管理金鑰直接建立）：demo-seed 必須走草稿與審核')
        return send(res, 201, { journey_id: ulid(), version: 1, status: 'ACTIVE' })
      }
      const isWrite = method !== 'GET'
      if (!me) return fail(res, 401, 'UNAUTHENTICATED', `${method} ${path} needs a session (Cookie header)`)
      if (isWrite && req.headers['x-xsrf-token'] !== sess!.token) return fail(res, 403, 'CSRF_INVALID', `${method} ${path} X-XSRF-TOKEN must equal the latest XSRF-TOKEN cookie`)
      if (method === 'POST' && path === '/api/v1/journeys/drafts') {
        if (me.role === 'VIEWER') return fail(res, 403, 'FORBIDDEN', 'viewer')
        const id = ulid()
        drafts.set(id, { name: body.name, revision: 1, definition: null, submittedBy: null, reviewId: null })
        return send(res, 201, { journey_id: id, status: 'DRAFT', revision: 1 })
      }
      const m = path.match(/^\/api\/v1\/journeys\/([^/]+)(\/.*)?$/)
      const d = m ? drafts.get(m[1]) : undefined
      if (!m || !d) return fail(res, 404, 'JOURNEY_NOT_FOUND', path)
      const sub = m[2] ?? ''
      if (method === 'GET' && sub === '') return send(res, 200, { journey_id: m[1], name: d.name, status: published.has(m[1]) ? 'ACTIVE' : 'DRAFT', latest_version: published.has(m[1]) ? 1 : null })
      if (method === 'PUT' && sub === '/draft') {
        if (d.reviewId) return fail(res, 409, 'DRAFT_IN_REVIEW', 'locked')
        if (body.revision !== d.revision) return fail(res, 409, 'DRAFT_CONFLICT', 'revision')
        d.revision += 1
        d.definition = body.definition
        return send(res, 200, { journey_id: m[1], revision: d.revision, definition: d.definition, layout: body.layout ?? null })
      }
      if (method === 'POST' && sub === '/draft/validate') return send(res, 200, { valid: true, errors: [], warnings: [] })
      if (method === 'POST' && sub === '/draft/submit') {
        if (me.role === 'VIEWER') return fail(res, 403, 'FORBIDDEN', 'viewer')
        if (!d.definition) return fail(res, 409, 'DRAFT_EMPTY', 'no definition')
        if (body.revision !== d.revision) return fail(res, 409, 'DRAFT_CONFLICT', 'revision')
        d.reviewId = ulid()
        d.submittedBy = me.email
        return send(res, 201, { review_id: d.reviewId, journey_id: m[1], status: 'PENDING' })
      }
      const ap = sub.match(/^\/reviews\/([^/]+)\/approve$/)
      if (method === 'POST' && ap) {
        if (me.role !== 'ADMIN') return fail(res, 403, 'FORBIDDEN', 'approve needs ADMIN')
        if (ap[1] !== d.reviewId) return fail(res, 409, 'REVIEW_NOT_PENDING', 'review')
        if (d.submittedBy === me.email) return fail(res, 403, 'SELF_APPROVAL_FORBIDDEN', 'self')
        published.add(m[1])
        return send(res, 200, { review_id: d.reviewId, status: 'APPROVED', version: 1 })
      }
    }
    return fail(res, 404, 'NOT_FOUND', `${method} ${path}`)
  }

  return { users, drafts, published, log, problems, handle }
}

function cyclic(def: Def): boolean {
  const out = new Map(def.nodes.map((n) => [n.id, Object.values(n.outputs).filter((v): v is string => typeof v === 'string')]))
  const state = new Map<string, number>()
  const visit = (id: string): boolean => {
    if (state.get(id) === 1) return true
    if (state.get(id) === 2) return false
    state.set(id, 1)
    for (const next of out.get(id) ?? []) if (visit(next)) return true
    state.set(id, 2)
    return false
  }
  return [...out.keys()].some(visit)
}

describe('make demo-seed：縮減版場景一與迴圈旅程', () => {
  const fake = fakeBackend()
  let server: Server
  let exitCode: number | null = null
  let killed = false
  let stdout = ''
  let stderr = ''

  beforeAll(async () => {
    server = createServer((req, res) => { fake.handle(req, res).catch((e) => { res.writeHead(500); res.end(String(e)) }) })
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
    const port = (server.address() as AddressInfo).port
    const child = spawn('python3', ['-I', SEED], {
      env: { ...process.env, API_PORT: String(port), CDP_ADMIN_API_KEY: ADMIN_KEY, CDP_INGEST_API_KEY: INGEST_KEY, CDP_DEMO_PASSWORD: PASSWORD },
    })
    child.stdout.on('data', (d) => { stdout += d })
    child.stderr.on('data', (d) => { stderr += d })
    const done = new Promise<void>((r) => child.on('close', (code) => { exitCode = code; r() }))
    // seed 結尾會輪詢進度最多 60 秒（假後端不模擬引擎）；兩個旅程都核准且事件送出後就結束它
    const started = Date.now()
    while (exitCode === null && Date.now() - started < 80_000) {
      const approved = fake.log.filter((r) => r.path.endsWith('/approve')).length
      const events = fake.log.filter((r) => r.path === '/api/v1/events').length
      if (approved >= 2 && events >= 1) {
        await new Promise((r) => setTimeout(r, 1500))
        if (exitCode === null) { killed = true; child.kill() }
        break
      }
      if (fake.problems.length) { child.kill(); killed = true; break }
      await new Promise((r) => setTimeout(r, 200))
    }
    if (exitCode === null) { killed = true; child.kill() }
    await done
  }, 120_000)

  afterAll(() => new Promise<void>((r) => server.close(() => r())))

  const defs = (): Def[] => [...fake.drafts.values()].map((d) => d.definition).filter((d): d is Def => !!d)
  const types = (d: Def) => new Set(d.nodes.map((n) => n.type))
  const scenarioOne = () => defs().find((d) => types(d).has('SplitTestNode'))
  const loop = () => defs().find((d) => cyclic(d))

  it('沒有被假後端擋下的請求（Cookie、XSRF 標頭、角色都對），seed 自己跑完或只因輪詢被結束', () => {
    expect(fake.problems, stderr + '\n' + stdout).toEqual([])
    if (!killed) expect(exitCode, stderr).toBe(0)
  })

  it('旅程都走草稿：editor 建草稿與送審、另一位 admin 核准，沒有用管理金鑰直接建立', () => {
    expect(fake.drafts.size).toBeGreaterThanOrEqual(2)
    for (const [id, d] of fake.drafts) {
      expect(fake.published.has(id), `${d.name} 應已核准上線`).toBe(true)
      const post = fake.log.find((r) => r.method === 'POST' && r.path === `/api/v1/journeys/${id}/draft/submit`)!
      const approve = fake.log.find((r) => r.method === 'POST' && r.path.startsWith(`/api/v1/journeys/${id}/reviews/`))!
      expect(fake.users.get(post.actor)?.role, '送審者').toBe('EDITOR')
      expect(fake.users.get(approve.actor)?.role, '核准者').toBe('ADMIN')
      expect(approve.actor).not.toBe(post.actor)
    }
    expect(fake.log.some((r) => r.method === 'POST' && r.path === '/api/v1/journeys')).toBe(false)
  })

  it('縮減版場景一：等事件、條件、A/B 分流、合流、達標、退出；沒有 Webhook 與更新客戶資料；進入事件有金額 > 500 篩選', () => {
    const d = scenarioOne()
    expect(d, '找不到含 SplitTestNode 的旅程').toBeTruthy()
    const t = types(d!)
    for (const need of ['EventSourceNode', 'WaitDelayNode', 'ConditionNode', 'SendMessageNode', 'MergeNode', 'WaitForEventNode', 'GoalNode', 'ExitNode', 'EndNode'])
      expect(t.has(need), need).toBe(true)
    expect(t.has('WebhookNode')).toBe(false)
    expect(t.has('UpdateProfileNode')).toBe(false)
    const src = d!.nodes.find((n) => n.type === 'EventSourceNode')!
    const flt = JSON.stringify(src.config.event_filter)
    expect(flt).toContain('GT')
    expect(flt).toContain('500')
    expect(d!.nodes.filter((n) => n.type === 'WaitForEventNode')).toHaveLength(2)
    const split = d!.nodes.find((n) => n.type === 'SplitTestNode')!
    expect(Object.keys(split.outputs).sort()).toEqual(['control', 'coupon'])
    // coupon 直接接訊息（拿掉 Webhook），第二個等結帳逾時直接接結束
    const byId = new Map(d!.nodes.map((n) => [n.id, n]))
    expect(byId.get(String(split.outputs.coupon))!.type).toBe('SendMessageNode')
    const waits = d!.nodes.filter((n) => n.type === 'WaitForEventNode')
    expect(waits.some((w) => byId.get(String(w.outputs.timeout))!.type === 'EndNode')).toBe(true)
  })

  it('迴圈旅程：圖有迴圈且迴圈裡有等待節點', () => {
    const d = loop()
    expect(d, '找不到有迴圈的旅程').toBeTruthy()
    expect(d!.nodes.some((n) => n.type === 'WaitForEventNode' || n.type === 'WaitDelayNode')).toBe(true)
  })

  it('不印出金鑰', () => {
    expect(stdout + stderr).not.toContain(ADMIN_KEY)
    expect(stdout + stderr).not.toContain(INGEST_KEY)
  })
})
