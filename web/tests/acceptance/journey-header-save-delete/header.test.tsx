// @vitest-environment jsdom
/**
 * journey-header-save-delete：旅程頁標頭（docs/ui/canvas-interaction.md §4、設計稿 #jh）。
 * 狀態不適用的動作不顯示；角色或送審鎖定才停用，原因整個標頭只寫一行（標題列下方，不在按鈕裡）；
 * 「⋯ 更多動作」收刪除旅程／停止旅程／捨棄草稿；分頁列「節點圖｜旅程進度｜審核紀錄」。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  AMY, JID, JOURNEY_URL, NEVER, REF_AMY, REF_DAMING, VIEWER, WANG, bodyText, cleanup, expectDisabled, expectEnabled, isDisabled, itemNamed, menuItems,
  moreButton, openJourney, openMenu, reasonIds, resetEnv, screen, teardown, userEvent, waitFor, within,
} from './helpers'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const tabs = () => screen.getByRole('navigation', { name: '旅程頁分頁' })
const VIEW = /檢視者.*只能查看/

describe('分頁列', () => {
  it('節點圖｜旅程進度｜審核紀錄三個連結（取代頁首三顆連結按鈕）；目前頁是「節點圖」', async () => {
    await openJourney({ me: AMY, kind: 'active' })
    const links = within(tabs()).getAllByRole('link')
    expect(links.map((l) => (l.textContent ?? '').trim())).toEqual(['節點圖', '旅程進度', '審核紀錄'])
    expect(links[0].getAttribute('aria-current')).toBe('page')
    expect(links[1].getAttribute('aria-current')).toBeNull()
    expect(links[1].getAttribute('href')).toContain('/enrollments')
    expect(links[1].getAttribute('href')).toContain(`journey_id=${JID}`)
    // 整頁只剩分頁列裡那一個，標頭動作區沒有重複的「審核紀錄」「旅程進度」
    expect(screen.getAllByRole('link', { name: '審核紀錄' }).length).toBe(1)
    expect(screen.getAllByRole('link', { name: '旅程進度' }).length).toBe(1)
  })

  it('檢視者也有分頁列；點「審核紀錄」離開旅程頁', async () => {
    await openJourney({ me: VIEWER, kind: 'active' })
    await userEvent.click(within(tabs()).getByRole('link', { name: '審核紀錄' }))
    await waitFor(() => expect(window.location.pathname).not.toBe(JOURNEY_URL))
  })

  it('分頁列在標題列之後（標題 → 說明 → 分頁列）', async () => {
    await openJourney({ me: AMY, kind: 'active' })
    const h1 = screen.getByRole('heading', { level: 1 })
    expect(h1.compareDocumentPosition(tabs()) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('「⋯ 更多動作」選單', () => {
  it('按鈕 aria-label「更多動作」、aria-haspopup=menu；收合時沒有選單，點開 aria-expanded=true 並出現 role=menu；Esc 收起並把焦點還給按鈕', async () => {
    await openJourney({ me: AMY, kind: 'editing' })
    const btn = moreButton()!
    expect(btn).toBeTruthy()
    expect(btn.getAttribute('aria-haspopup')).toBe('menu')
    expect(btn.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(screen.queryByRole('menuitem')).toBeNull()
    await userEvent.click(btn)
    expect(btn.getAttribute('aria-expanded')).toBe('true')
    expect(screen.getByRole('menu')).toBeTruthy()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
    expect(btn.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(btn)
  })

  it('刪除旅程、停止旅程、捨棄草稿只在選單裡：收合時整頁沒有這三個動作', async () => {
    await openJourney({ me: AMY, kind: 'editing' })
    for (const n of ['刪除旅程', '停止旅程', '捨棄草稿']) {
      expect(screen.queryByRole('button', { name: n }), `收合時不應有「${n}」按鈕`).toBeNull()
      expect(screen.queryByRole('link', { name: n })).toBeNull()
    }
  })

  it('從未發佈（DRAFT）：選單只有「刪除旅程」；沒有停止旅程、捨棄草稿（不顯示，不是停用）', async () => {
    await openJourney({ me: AMY, kind: 'neverPublished' })
    const menu = await openMenu()
    expect(itemNamed(menu, '刪除旅程')).toBeTruthy()
    expect(itemNamed(menu, '停止旅程')).toBeNull()
    expect(itemNamed(menu, '捨棄草稿')).toBeNull()
    expect(menuItems(menu).length).toBe(1)
    expect(isDisabled(itemNamed(menu, '刪除旅程')!)).toBe(false)
  })

  it('進行中、沒有草稿：選單只有「停止旅程」（已發佈過不能刪除旅程，也沒有草稿可捨棄）', async () => {
    await openJourney({ me: AMY, kind: 'active' })
    const menu = await openMenu()
    expect(menuItems(menu).length).toBe(1)
    expect(itemNamed(menu, '停止旅程')).toBeTruthy()
    expect(itemNamed(menu, '刪除旅程')).toBeNull()
    expect(itemNamed(menu, '捨棄草稿')).toBeNull()
  })

  it('進行中、有草稿：選單有「停止旅程」「捨棄草稿」，沒有「刪除旅程」', async () => {
    await openJourney({ me: WANG, kind: 'editing' })
    const menu = await openMenu()
    expect(itemNamed(menu, '停止旅程')).toBeTruthy()
    expect(itemNamed(menu, '捨棄草稿')).toBeTruthy()
    expect(itemNamed(menu, '刪除旅程')).toBeNull()
    expect(menuItems(menu).length).toBe(2)
  })

  it('已停止：沒有刪除旅程、捨棄草稿，停止旅程不可用', async () => {
    await openJourney({ me: WANG, kind: 'aborted' })
    const btn = moreButton()
    if (btn) {
      const menu = await openMenu().catch(() => null)
      if (menu) {
        expect(itemNamed(menu, '刪除旅程')).toBeNull()
        expect(itemNamed(menu, '捨棄草稿')).toBeNull()
        const stop = itemNamed(menu, '停止旅程')
        if (stop) expect(isDisabled(stop)).toBe(true)
      }
    }
  })
})

describe('停用的動作：只有角色或送審鎖定才停用，原因整個標頭只寫一行', () => {
  const reasonEl = (id: string) => document.getElementById(id) as HTMLElement

  it('檢視者看進行中的旅程：編輯、停止旅程停用，共用同一行原因（標題列下方、分頁列上方、不在按鈕或選單內）', async () => {
    await openJourney({ me: VIEWER, kind: 'active' })
    const edit = await expectDisabled('編輯', VIEW)
    const stop = await expectDisabled('停止旅程', VIEW)
    const ids = new Set([...reasonIds(edit), ...reasonIds(stop)])
    expect(ids.size, '標頭只有一行停用原因').toBe(1)
    const why = reasonEl([...ids][0])
    const h1 = screen.getByRole('heading', { level: 1 })
    expect(h1.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING, '原因在標題之後').toBeTruthy()
    expect(why.compareDocumentPosition(tabs()) & Node.DOCUMENT_POSITION_FOLLOWING, '原因在分頁列之前').toBeTruthy()
    expect(why.closest('button, [role="menu"], [role="menuitem"]'), '原因不在按鈕或選單裡').toBeNull()
    expect(moreButton()!.parentElement!.contains(why), '原因不在動作區容器裡').toBe(false)
    // 同一句話整頁只出現一次
    expect(bodyText().split('只能查看').length - 1).toBe(1)
  })

  it('送審中（送審者本人，從未發佈）：編輯停用、刪除旅程停用，同一行原因說明已鎖定、要先撤回送審；撤回送審可用', async () => {
    await openJourney({ me: AMY, kind: 'pending', submitter: REF_AMY, detail: NEVER })
    const edit = await expectDisabled('編輯', /鎖定/)
    expect(reasonOf2(edit)).toMatch(/撤回送審/)
    await expectEnabled('撤回送審')
    const menu = await openMenu()
    const del = itemNamed(menu, '刪除旅程')!
    expect(del, '送審中的刪除旅程停用、仍保留').toBeTruthy()
    expect(del.getAttribute('aria-disabled')).toBe('true')
    expect(reasonOf2(del)).toMatch(/撤回送審/)
    expect(new Set([...reasonIds(edit), ...reasonIds(del)]).size, '同一行原因').toBe(1)
    expect(itemNamed(menu, '停止旅程')).toBeNull()
    expect(itemNamed(menu, '捨棄草稿')).toBeNull()
  })

  it('送審中（別人送的，編輯者）：所有停用動作合起來最多引用一行原因', async () => {
    await openJourney({ me: AMY, kind: 'pending', submitter: REF_DAMING, detail: NEVER })
    await openMenu()
    const off = [...document.querySelectorAll<HTMLElement>('button, a, [role="menuitem"]')].filter((e) => e.getAttribute('aria-disabled') === 'true')
    expect(off.length).toBeGreaterThanOrEqual(2) // 至少「撤回送審」（非送審者）與「編輯」（鎖定）
    const ids = new Set(off.flatMap((e) => reasonIds(e)))
    expect(ids.size).toBeLessThanOrEqual(1)
    for (const e of off) expect(reasonIds(e).length, `「${e.textContent}」停用卻沒有原因`).toBeGreaterThan(0)
  })

  it('停用動作點下去：不開對話框、不送請求（選單項也一樣）', async () => {
    const { api } = await openJourney({ me: AMY, kind: 'pending', submitter: REF_AMY, detail: NEVER })
    const menu = await openMenu()
    await userEvent.click(itemNamed(menu, '刪除旅程')!)
    await userEvent.click(await expectDisabled('編輯', /鎖定/))
    expect(screen.queryByRole('alertdialog')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.calls.filter((c) => c.method !== 'GET')).toEqual([])
  })

  it('可用的狀態沒有停用說明列：編輯者看進行中旅程，頁面沒有任何 aria-disabled 動作', async () => {
    await openJourney({ me: AMY, kind: 'active' })
    await openMenu()
    expect([...document.querySelectorAll('[aria-disabled="true"]')].filter((e) => e.matches('button, a, [role="menuitem"]'))).toEqual([])
  })
})

function reasonOf2(el: Element) {
  return reasonIds(el).map((i) => document.getElementById(i)?.textContent ?? '').join(' ')
}
