// @vitest-environment jsdom
/**
 * node-card-unify：事件篩選的節點卡摘要（docs/ui/canvas-interaction.md §2「截斷」）。
 * 1 項寫成「大於 500・購物車金額」（比較與值在前、欄位名在後），不再是「只限『購物車金額』大於 500」；2 項以上「符合 N 項條件」。
 * 進入點與 fixture 同 event-filter-canvas-ui。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { and, cleanup, efBackend, efDef, leaf, nodeButton, openEditor, resetEnv, teardown, waitFor } from '../event-filter-canvas-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => {
  cleanup()
  teardown()
})

const text = (name: string) => (nodeButton(name).textContent ?? '').replace(/\s+/g, ' ')

describe('事件篩選摘要', () => {
  it('1 項：「大於 500・購物車金額」，沒有「只限」', async () => {
    efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500))))
    await openEditor()
    const t = await waitFor(() => {
      const s = text('加入購物車')
      expect(s).toContain('購物車金額')
      return s
    })
    expect(t).toMatch(/大於 ?500・購物車金額/)
    expect(t).not.toContain('只限')
    expect(t.indexOf('大於'), '比較與值在前').toBeLessThan(t.indexOf('購物車金額'))
  })

  it('2 項以上：「符合 2 項條件」', async () => {
    efBackend(efDef(and(leaf('properties.cart_total', 'GT', 500), leaf('properties.item_count', 'GTE', 2))))
    await openEditor()
    await waitFor(() => expect(text('加入購物車')).toContain('符合 2 項條件'))
    expect(text('加入購物車')).not.toContain('只限')
  })

  it('沒有篩選：不出現「條件」字樣', async () => {
    efBackend()
    await openEditor()
    await waitFor(() => expect(nodeButton('加入購物車')).toBeTruthy())
    expect(text('加入購物車')).not.toMatch(/條件|大於/)
  })
})
