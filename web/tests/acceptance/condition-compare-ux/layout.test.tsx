// @vitest-environment jsdom
/**
 * condition-compare-ux：斷點下的實際排版（docs/ui/canvas-interaction.md §3）。
 * 比較欄固定 176px、欄位另佔一行；比較＋值同一行；≤640px 直向各 44px 高。
 * jsdom 不算版面：layout.ts 依視窗寬度攤平 app.css 的 @media、注入文件後對真實元素讀 getComputedStyle（層疊結果）。
 * 讀得到 flex-basis／width／min-height 即可，不在乎寫法。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, resetEnv, teardown, within } from '../journey-canvas-ui/fixtures'
import { andOf, cartLeaf, openCond } from './fixtures'
import { boxHeight, cssAt, fixedWidth, stacked, withViewport } from './layout'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

async function parts() {
  const { panel } = await openCond(andOf(cartLeaf('GT', 500)))
  const r = within(panel).getAllByLabelText('欄位')[0].closest('li, .cond-row') as HTMLElement
  return { field: within(r).getByLabelText('欄位'), cmp: within(r).getByLabelText('比較'), val: within(r).getByLabelText('值'), remove: within(r).getByRole('button', { name: '移除這個條件' }) }
}

describe('輔助：攤平規則本身正確（避免測試恆過）', () => {
  it('max-width 區塊只在視窗寬度不超過時才併入', () => {
    expect(cssAt(375)).toMatch(/\.panel-collapse[^{]*\{[^}]*display:\s*none/)
    expect(cssAt(1440)).not.toMatch(/\.panel-collapse[^{]*\{[^}]*display:\s*none/)
  })
})

describe('1440 與 768：比較欄 176px、同一行', () => {
  it.each([1440, 768])('%ipx：比較欄寬 176px；欄位另佔一行；比較與值不直向堆疊；三個控制項 44px 高', async (w) => {
    const { field, cmp, val } = await parts()
    withViewport(w, () => {
      expect(fixedWidth(cmp)).toBe(176)
      expect(stacked(field)).toBe(true)
      expect(stacked(cmp)).toBe(false)
      expect(stacked(val)).toBe(false)
      expect(boxHeight(cmp)).toBeGreaterThanOrEqual(44)
      expect(boxHeight(val)).toBeGreaterThanOrEqual(44)
      expect(boxHeight(field)).toBeGreaterThanOrEqual(44)
    })
  })

  it.each([1440, 768])('%ipx：條件卡三個控制項字級同為 16px', async (w) => {
    const { field, cmp, val } = await parts()
    withViewport(w, () => {
      for (const el of [field, cmp, val]) expect(getComputedStyle(el).fontSize).toBe('16px')
    })
  })
})

describe('375（≤640px）：比較與值直向各 44px', () => {
  it('比較與值各自一行（直向）、高度 >= 44px，欄位仍整行；比較欄不再固定 176 擠在一起', async () => {
    const { field, cmp, val } = await parts()
    withViewport(375, () => {
      expect(stacked(field)).toBe(true)
      expect(stacked(cmp)).toBe(true)
      expect(stacked(val)).toBe(true)
      expect(boxHeight(cmp)).toBeGreaterThanOrEqual(44)
      expect(boxHeight(val)).toBeGreaterThanOrEqual(44)
      expect(boxHeight(field)).toBeGreaterThanOrEqual(44)
    })
  })

  it('640px 邊界仍是直向；641px 已是同一行並回到 176px', async () => {
    const { cmp, val } = await parts()
    withViewport(640, () => {
      expect(stacked(cmp)).toBe(true)
      expect(stacked(val)).toBe(true)
    })
    withViewport(641, () => {
      expect(fixedWidth(cmp)).toBe(176)
      expect(stacked(cmp)).toBe(false)
    })
  })
})
