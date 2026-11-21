// 三處節點卡（編輯畫布、旅程頁、軌跡圖）共用的尺寸與初始縮放規則。
// 規格：docs/ui/canvas-interaction.md §2。dagre 排版與連線接點（handle）都用這組常數。

/** 卡片寬 */
export const CARD_W = 200
/** 卡片高：類別、名稱（最多 2 行）、摘要 */
export const CARD_H = 120
/** 卡片高：另有一行人數或狀態（旅程頁有統計、軌跡頁） */
export const CARD_H_STAT = 144

/** 自動縮放的下限、上限；手機找不到不切字的值時可放大到 PHONE_MAX */
export const ZOOM_MIN_AUTO = 0.75
export const ZOOM_MAX_AUTO = 1
export const ZOOM_PHONE_MAX = 1.25
/** 右緣只允許露出下一張卡的色條（流程座標，色條加邊框 7px，留餘裕到 12） */
const EXPOSE_MAX = 12

/**
 * 初始縮放（三處同一規則）：
 * - 整張放得下 → 100%；縮到 75%～100% 就放得下 → 縮到剛好放得下
 * - 75% 仍放不下 → 取 75%～100% 中最大、且右緣不切在卡片文字中間的值；找不到再放大到 125%；都沒有就 75%
 * 縮放值取到 0.01，畫面上的百分比與實際一致。
 * @param fitW 可用來放整張圖的寬度
 * @param contentW 整張圖的寬度
 * @param rightAt 縮放為 z 時，可見範圍右緣的流程座標
 * @param xs 每張卡的左緣流程座標
 */
export function pickZoom(fitW: number, contentW: number, rightAt: (z: number) => number, xs: readonly number[]): number {
  if (!(fitW > 0) || !(contentW > 0)) return 1
  const zfit = Math.floor((fitW / contentW) * 100) / 100
  if (zfit >= ZOOM_MAX_AUTO) return ZOOM_MAX_AUTO
  if (zfit >= ZOOM_MIN_AUTO) return zfit
  const ok = (z: number) => {
    const r = rightAt(z)
    return !xs.some((x) => r > x + EXPOSE_MAX && r < x + CARD_W)
  }
  for (let p = ZOOM_MAX_AUTO * 100; p >= ZOOM_MIN_AUTO * 100; p--) if (ok(p / 100)) return p / 100
  for (let p = ZOOM_MAX_AUTO * 100 + 1; p <= ZOOM_PHONE_MAX * 100; p++) if (ok(p / 100)) return p / 100
  return ZOOM_MIN_AUTO
}

/** 出口圓點半徑（直徑 16、觸控區 32）；選取最右欄節點時要把它算進可見範圍 */
export const HANDLE_R = 8
/** 編輯畫布連線繞到欄外的距離 */
export const ROUTE_OUT = 24

/** 右緣第 index 個出口（共 count 個）的 y（相對卡片上緣）：單一出口在中點，多個出口等距分布；CSS 位置與連線端點共用 */
export function outletY(index: number, count: number): number {
  return count <= 1 ? CARD_H / 2 : (CARD_H * (index + 1)) / (count + 1)
}
