// @vitest-environment jsdom
/** event-filter-canvas-ui：節點卡白話摘要、送審中面板唯讀（原生 readOnly）、視覺評審 3 項中可由 CSS 檢查的一項。 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DRAFT_PATH, SRC_LEGEND, and, cleanup, draftBody, efBackend, efDef, json, leaf, nodeButton, open, resetEnv, rowsOf, screen,
  selectNode, teardown, userEvent, waitFor, within, bodyText, openEditor,
} from './fixtures'
import { brief, review } from '../journey-review-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const THREE = and(leaf('properties.order_total', 'GTE', 500), leaf('properties.payment_method', 'IN', ['credit_card', 'line_pay']), leaf('properties.coupon_code', 'NEQ', 'TEST100'))

describe('節點卡摘要', () => {
  it('沒有篩選：維持原摘要（不出現「條件」字樣）', async () => {
    efBackend()
    await openEditor()
    await waitFor(() => expect(nodeButton('加入購物車')).toBeTruthy())
    expect(nodeButton('加入購物車').textContent).not.toContain('只限')
    expect(nodeButton('加入購物車').textContent).not.toMatch(/\d+ 項條件/)
  })

  it('1 項：「只限…」白話句含「購物車金額」「大於 500」', async () => {
    efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500))))
    await openEditor()
    const t = await waitFor(() => nodeButton('加入購物車').textContent ?? '')
    expect(t).toContain('只限')
    expect(t).toContain('購物車金額')
    expect(t).toMatch(/大於\s*500/)
  })

  it('2 項以上：入口寫「符合 N 項條件」；等事件寫「24 小時・N 項條件」', async () => {
    efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500), leaf('properties.item_count', 'GTE', 2)), THREE))
    await openEditor()
    await waitFor(() => expect(nodeButton('加入購物車').textContent).toContain('符合 2 項條件'))
    expect(nodeButton('等結帳 24h').textContent).toContain('24 小時・3 項條件')
  })
})

describe('送審中：篩選區塊唯讀', () => {
  const REVIEW = review()
  const lockedDraft = () => draftBody({ revision: 4, definition: efDef(and(leaf('properties.cart_total', 'GT', 500))), latest_review: REVIEW })
  const lockedBackend = () =>
    efBackend(efDef(), { [`GET ${DRAFT_PATH}`]: () => json(lockedDraft()) }, { detail: { review_status: 'PENDING', latest_review: brief(REVIEW) } })

  it('名稱、事件名稱、值為原生 readOnly；欄位、比較停用；沒有可用的新增／移除／清除按鈕', async () => {
    lockedBackend()
    const g = await open('加入購物車')
    expect(bodyText()).toContain('草稿已鎖定，不能修改')
    expect((screen.getByLabelText('名稱') as HTMLInputElement).hasAttribute('readonly')).toBe(true)
    expect((screen.getByLabelText(/事件名稱/) as HTMLInputElement).hasAttribute('readonly')).toBe(true)
    const row = rowsOf(g)[0]
    expect((within(row).getByLabelText('值') as HTMLInputElement).hasAttribute('readonly')).toBe(true)
    expect((within(row).getByLabelText('欄位') as HTMLSelectElement).disabled).toBe(true)
    expect((within(row).getByLabelText('比較') as HTMLSelectElement).disabled).toBe(true)
    for (const name of ['加入條件', '移除這個條件', '清除篩選', '加入群組']) {
      for (const b of within(g).queryAllByRole('button', { name })) {
        expect((b as HTMLButtonElement).disabled || b.getAttribute('aria-disabled') === 'true', `「${name}」要停用`).toBe(true)
      }
    }
  })

  it('鎖定時操作不會送出 PUT', async () => {
    const ed = lockedBackend()
    const g = await open('加入購物車')
    await userEvent.type(within(rowsOf(g)[0]).getByLabelText('值'), '9')
    await new Promise((r) => setTimeout(r, 1500))
    expect(ed.puts().length).toBe(0)
  })

  it('未鎖定時名稱欄沒有 readonly', async () => {
    efBackend()
    await openEditor()
    await selectNode('加入購物車')
    expect((await screen.findByLabelText('名稱') as HTMLInputElement).hasAttribute('readonly')).toBe(false)
    void SRC_LEGEND
  })
})

describe('視覺評審項目（CSS）', () => {
  it('`.uhead strong` 規則只出現在 max-width: 640px 的 media query 內（不在全域）', () => {
    const here = dirname(fileURLToPath(import.meta.url))
    const css = readFileSync(resolve(here, '../../../src/styles/app.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '')
    const inside: string[] = []
    let outside = css
    const re = /@media[^{]*max-width:\s*640px[^{]*\{/g
    while (re.exec(css)) {
      let depth = 1
      let i = re.lastIndex
      while (i < css.length && depth > 0) { if (css[i] === '{') depth++; else if (css[i] === '}') depth--; i++ }
      const block = css.slice(re.lastIndex, i - 1)
      inside.push(block)
      outside = outside.replace(block, '')
    }
    expect(inside.join('\n')).toMatch(/\.uhead\s+strong/)
    expect(outside).not.toMatch(/\.uhead\s+strong/)
  })
})
