// @vitest-environment jsdom
/**
 * canvas-connect-drag：視覺評審問題 2（選取最右欄節點時出口圓點被切／被漸層蓋）。
 * 評審問題 1（回頭線移除鈕超出可見範圍）與拖拉、選單、移除鈕位置，jsdom 量不到，由 uat 目視。假設見 helpers.tsx。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, editorBackend, resetEnv, selectNode, smallDef, teardown, waitFor } from '../journey-canvas-ui/fixtures'
import type { Def } from '../journey-canvas-ui/fixtures'
import { openBare } from './helpers'
import { BOX, mockLayout, nodeRects, restoreLayout, viewport } from '../canvas-fit-fix/fixtures'

/** 最右欄是有出口的節點（等待，下一步沒接）；長鏈使整張圖比畫布寬 */
const longDef = (): Def => {
  const d = smallDef()
  return {
    ...d,
    nodes: [
      ...d.nodes.map((n) => (n.id === 'end_1' ? { ...n, type: 'WaitDelayNode', name: '最後等待', config: { duration: 'PT1H' }, outputs: {} } : n)),
    ],
  }
}

describe('選取最右欄節點：出口圓點不被切、不被右緣漸層蓋', () => {
  beforeEach(() => { resetEnv(); mockLayout(); BOX.w = 1000 })
  afterEach(() => { cleanup(); teardown(); restoreLayout(); BOX.w = 1000 })

  for (const w of [1000, 1400]) {
    it(`畫布 ${w} 寬：選取「最後等待」後，卡片右緣＋出口圓點（半徑 8）＋48px 漸層都在畫布內`, async () => {
      BOX.w = w
      editorBackend({ def: longDef(), revision: 1 })
      await openBare()
      await waitFor(() => expect(nodeRects().length).toBe(6))
      await selectNode('最後等待')
      await waitFor(() => {
        const r = nodeRects().find((x) => x.name.includes('最後等待'))!
        const z = viewport().zoom
        expect(r.x + r.w + 8 * z + 48, `卡右緣 ${Math.round(r.x + r.w)}，畫布 ${w}`).toBeLessThanOrEqual(w + 0.5)
        expect(r.x).toBeGreaterThanOrEqual(-0.5)
      }, { timeout: 4000 })
    }, 60000)
  }
})
