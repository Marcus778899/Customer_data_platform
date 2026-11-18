// @vitest-environment jsdom
/**
 * canvas-layout-fix：uat v0.3.0 F-3／F-11 畫布存檔要分層排版（dagre 由左到右，docs/ui/journey-builder.md「畫布排版」）。
 * 重現：空草稿依序加節點、接線後，PUT 的 layout 不可是一整列；連線方向由左到右、分支分叉、節點不重疊。
 * 假設同 journey-canvas-ui/fixtures.tsx（工具列、右側面板「是／否／送出後」下一步選單、自動儲存 PUT）。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  addNode, chooseLabel, cleanup, editorBackend, nodeById, openEditor, rename, resetEnv, selectNode, skeleton, teardown,
  waitForPut, byName, userEvent, type Def, type PutBody,
} from '../journey-canvas-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

const W = 148
const H = 72
type Pos = { x: number; y: number }
const posOf = (b: PutBody, d: Def, name: string): Pos => b.layout!.nodes[byName(d, name)!.id]

describe('畫布存檔的排版（F-3、F-11）', () => {
  it('條件分兩路再合流：存檔的 layout 由左到右分層、是／否分叉、節點不重疊', async () => {
    const ed = editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await addNode('條件'); await rename('分流點')
    await addNode('訊息'); await rename('甲信')
    await addNode('訊息'); await rename('乙信')
    await addNode('結束'); await rename('收尾')

    await selectNode('分流點')
    await chooseLabel('是', ['甲信'])
    await chooseLabel('否', ['乙信'])
    await selectNode('甲信')
    await chooseLabel('送出後', ['收尾'])
    await selectNode('乙信')
    await chooseLabel('送出後', ['收尾'])

    const put = await waitForPut((b) => {
      const c = byName(b.definition, '分流點')
      const a = byName(b.definition, '甲信')
      const o = byName(b.definition, '乙信')
      return !!c && !!a && !!o && c.outputs.yes === a.id && c.outputs.no === o.id && a.outputs.sent !== undefined && o.outputs.sent !== undefined
    }, ed)
    const d = put.definition
    const cond = posOf(put, d, '分流點')
    const a = posOf(put, d, '甲信')
    const o = posOf(put, d, '乙信')
    const end = posOf(put, d, '收尾')

    // 每條連線都往右（沒有迴圈時不應出現回邊）
    for (const n of d.nodes) {
      for (const t of Object.values(n.outputs)) {
        const targets = Array.isArray(t) ? t : [t]
        for (const id of targets as string[]) {
          const from = put.layout!.nodes[n.id]
          const to = put.layout!.nodes[id]
          expect(to.x, `${n.id} → ${id} 應由左到右`).toBeGreaterThan(from.x)
        }
      }
    }
    // 是／否兩路分叉：同一層、上下不同列，且不是一整列
    expect(a.x).toBe(o.x)
    expect(Math.abs(a.y - o.y)).toBeGreaterThanOrEqual(H)
    expect(end.x).toBeGreaterThan(a.x)
    expect(cond.x).toBeLessThan(a.x)
    // 節點不重疊
    const all = d.nodes.map((n) => put.layout!.nodes[n.id])
    for (let i = 0; i < all.length; i++) {
      for (let j = i + 1; j < all.length; j++) {
        const overlap = Math.abs(all[i].x - all[j].x) < W && Math.abs(all[i].y - all[j].y) < H
        expect(overlap, `節點 ${i} 與 ${j} 重疊`).toBe(false)
      }
    }
    expect(nodeById(d, 'start')).toBeTruthy()
  }, 60000)

  it('接線後節點整體不是一整列：分支旅程的 layout 至少有兩個不同的 y', async () => {
    const ed = editorBackend({ def: skeleton(), revision: 1 })
    await openEditor()
    await addNode('A/B 分流'); await rename('分組')
    await addNode('結束'); await rename('結束甲')
    await addNode('結束'); await rename('結束乙')
    await selectNode('分組')
    const put0 = await waitForPut((b) => !!byName(b.definition, '結束乙'), ed)
    const split = byName(put0.definition, '分組')!
    const ports = Object.keys(split.outputs).length
    // A/B 分流的出口標籤依變體而定；用面板內第一、第二個「下一步」選單接兩個結束
    const selects = [...document.querySelectorAll<HTMLSelectElement>('aside select, [role="complementary"] select')]
      .filter((s) => [...s.options].some((o) => o.textContent === '結束甲'))
    expect(selects.length, `A/B 分流面板應有至少兩個出口選單（目前出口 ${ports}）`).toBeGreaterThanOrEqual(2)
    await userEvent.selectOptions(selects[0], [...selects[0].options].find((o) => o.textContent === '結束甲')!.value)
    await userEvent.selectOptions(selects[1], [...selects[1].options].find((o) => o.textContent === '結束乙')!.value)
    const put = await waitForPut((b) => {
      const s = byName(b.definition, '分組')!
      return new Set(Object.values(s.outputs)).size >= 2
    }, ed)
    const d = put.definition
    const ys = new Set(['結束甲', '結束乙'].map((n) => posOf(put, d, n).y))
    expect(ys.size, '兩個分支出口應分上下兩列').toBe(2)
  }, 60000)
})
