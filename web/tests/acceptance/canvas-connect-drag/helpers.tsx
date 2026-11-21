/**
 * canvas-connect-drag 共用（docs/ui/canvas-interaction.md §1、設計稿 docs/ui/canvas-interaction/index.html 第 1 區）。
 *
 * jsdom 沒有版面與真正的指標拖曳，所以只測「結果」與可由 DOM 看到的行為；按住拖拉（拖入新增、拉線、放開在空白處跳選單、
 * 游標在節點上的綠框、選單位置、移除鈕在可見範圍內的位置）由 uat 在瀏覽器目視，不在這裡測。
 *
 * 對 worker 的假設（規格沒寫死的，全部列在這裡）：
 *   - 節點庫：`<aside aria-label="節點庫" class="nodelib">`（role=complementary），取代 role=toolbar「加入節點」與 details.addmenu。
 *     九個項目都是 `button`，accessible name／文字＝「名稱＋一句白話說明」，名稱開頭是節點名稱（訊息、等待、等事件、條件、A/B 分流、合流、
 *     達標、退出、結束）；分四組（發送、等待、分支、結果）；項目 `title` 含名稱。草稿送審鎖定時項目 `aria-disabled="true"`（照既有「停用鈕保留」）。
 *   - 出口圓點：節點外層容器（`.react-flow__node`）內、卡片 `button` 之外的獨立 `button`（不巢狀）；aria-label
 *     「從『X』的『是』出口拉線到下一步」（多出口）或「從『X』的出口拉線到下一步」（單一出口）；引號接受 「」 或 『』。
 *     已接好 `data-connected="true"`、未接 `data-connected="false"`（未接者一直顯示、橘色虛線）。
 *     只有節點 `outputSpecs` 的必填出口一定有圓點；選填出口（訊息的「被略過時」）是否有圓點不測。
 *   - 出口圓點按 Enter：把焦點移到右側面板對應出口的「下一步」下拉（label 是出口名稱：是／否／送出後／…，沿用現有面板）。
 *   - 面板「下一步」下拉不分選填，第一個選項（value ''）：必填出口文字「（還沒接）」（取代「（尚未連線）」）；選填出口維持「不處理，旅程結束」。
 *   - 邊：沿用 `data-testid="rf__edge-<來源 id>:<出口代碼>"`；點邊的 `path` 選取，選取後出現名稱含「移除連線」的 `button`
 *     （長線是膠囊鈕、短線是 44px 圓鈕，aria-label／title 都含「移除連線」）；按它或按 Delete 即移除，面板選「（還沒接）」同效。
 *     操作後 role=status 的 live region 文字含「已移除連線」。
 *   - 狀態：畫布有一段文字「還有 N 個出口沒接下一步」（N 只算必填出口）；全部接好時不顯示這句。
 *   - 首次提示：畫布內（不在 `.react-flow__viewport` 之內，所以不跟著捲動／縮放）一個含「從節點右邊的圓點按住」的元素與按鈕「知道了」；
 *     按「知道了」後不再出現，記在 localStorage（鍵名不測，測同一個 localStorage 下重開編輯器）。
 *   - 路徑 `d` 用絕對座標指令 M L C Q H V（無相對指令、無弧線）；端點座標見 node-card-unify/helpers.tsx anchorMismatches。
 *   - 自動儲存仍在（手動儲存是之後 journey-header-save-delete 的事），PUT 帶 definition／layout。
 */
import { expect } from 'vitest'
import { EDIT, nodeButtons, screen, start, waitFor, within } from '../journey-canvas-ui/fixtures'
import type { Def } from '../journey-canvas-ui/fixtures'
import { edgeEnds, readBoxes } from '../node-card-unify/helpers'

export { edgeEnds, readBoxes }
export type { Def }

/** 開編輯器、等節點畫出來（不依賴節點庫，讓不是測節點庫的測試只因自己的原因失敗） */
export async function openBare() {
  start(EDIT)
  await waitFor(() => expect(nodeButtons().length).toBeGreaterThan(0))
}

export const lib = () => screen.getByRole('complementary', { name: '節點庫' })

/** 某節點（以顯示名稱）的出口圓點 */
export function outHandles(nodeName: string): HTMLButtonElement[] {
  return [...document.querySelectorAll<HTMLButtonElement>('button[aria-label]')].filter((b) => {
    const l = b.getAttribute('aria-label') ?? ''
    return (l.startsWith(`從「${nodeName}」`) || l.startsWith(`從『${nodeName}』`)) && l.endsWith('拉線到下一步') && !!b.closest('.react-flow__node')
  })
}
/** port 是出口名（是、否…）；多出口的 aria-label 含「的『是』出口」 */
export function handleOf(nodeName: string, port?: string): HTMLButtonElement {
  const hit = outHandles(nodeName).find((b) => !port || /的[「『]/.test(b.getAttribute('aria-label')!.replace(/^從[「『].*?[」』]/, '')) && b.getAttribute('aria-label')!.includes(`${port}`) )
  if (!hit) throw new Error(`找不到「${nodeName}」${port ?? ''}的出口圓點；現有：${outHandles(nodeName).map((b) => b.getAttribute('aria-label')).join('｜')}`)
  return hit
}

/** 一條邊 path 的所有座標點（含曲線控制點；指令限 M L C Q H V 絕對座標） */
export function pathPoints(d: string): Array<[number, number]> {
  const pts: Array<[number, number]> = []
  let cx = 0
  let cy = 0
  for (const m of d.matchAll(/([MLCQHV])([^MLCQHV]*)/g)) {
    const nums = (m[2].match(/-?\d+(?:\.\d+)?(?:e-?\d+)?/gi) ?? []).map(Number)
    if (m[1] === 'H') {
      for (const n of nums) { cx = n; pts.push([cx, cy]) }
    } else if (m[1] === 'V') {
      for (const n of nums) { cy = n; pts.push([cx, cy]) }
    } else {
      for (let i = 0; i + 1 < nums.length; i += 2) { cx = nums[i]; cy = nums[i + 1]; pts.push([cx, cy]) }
    }
  }
  return pts
}
export const edgePoints = (edgeId: string) => {
  const wrap = document.querySelector(`[data-testid="rf__edge-${edgeId}"]`)
  const p = wrap?.querySelector<SVGPathElement>('path.react-flow__edge-path')
  if (!p) throw new Error(`找不到邊 ${edgeId}；現有：${edgeEnds().map((e) => e.id).join(',')}`)
  return pathPoints(p.getAttribute('d') ?? '')
}
/** 路徑（排除起訖兩端附近）有座標點落在某張卡內部（縮 4px），回傳說明 */
export function pointsInsideCards(points: Array<[number, number]>): string[] {
  const out: string[] = []
  for (const b of readBoxes()) {
    for (const [x, y] of points) if (x > b.x + 4 && x < b.x + b.w - 4 && y > b.y + 4 && y < b.y + b.h - 4) out.push(`(${x},${y}) 在「${b.name}」裡`)
  }
  return out
}

/** 等 PUT 帶出的定義 */
export const outputsOf = (def: Def, id: string) => def.nodes.find((n) => n.id === id)?.outputs ?? {}
export const refsTo = (def: Def, id: string) =>
  def.nodes.flatMap((n) => Object.entries(n.outputs).filter(([, v]) => v === id || (Array.isArray(v) && v[0] === id)).map(([k]) => `${n.id}.${k}`))

export const settle = () => new Promise((r) => setTimeout(r, 120))

/** 開始→進入事件→等待→條件（兩個出口都沒接）；用來測自動接線與未接出口 */
export const branchDef = (): Def => ({
  name: '分支旅程', description: null, settings: {}, entry_node: 'start',
  nodes: [
    { id: 'start', type: 'StartNode', name: null, config: {}, outputs: { next: 'src_1' } },
    { id: 'src_1', type: 'EventSourceNode', name: '加入購物車', config: { event_name: 'cart_item_added' }, outputs: { next: 'wait_1' } },
    { id: 'wait_1', type: 'WaitDelayNode', name: '等 1 小時', config: { duration: 'PT1H' }, outputs: { next: 'cond_1' } },
    { id: 'cond_1', type: 'ConditionNode', name: '分流點', config: { condition: { op: 'AND', children: [{ type: 'attribute', field: 'tier', operator: 'EQ', value: 'VIP' }] } }, outputs: {} },
  ],
})
/** 條件「是」接結束、「否」沒接（必填出口 1 個沒接）；另有一個沒人指向的結束 */
export const oneFreeDef = (): Def => ({
  ...branchDef(),
  nodes: [
    ...branchDef().nodes.map((n) => (n.id === 'cond_1' ? { ...n, outputs: { yes: 'end_a' } } : n)),
    { id: 'end_a', type: 'EndNode', name: '流程結束', config: {}, outputs: {} },
    { id: 'end_b', type: 'EndNode', name: '另一個結束', config: {}, outputs: {} },
  ],
})

export { waitFor, within, screen }
