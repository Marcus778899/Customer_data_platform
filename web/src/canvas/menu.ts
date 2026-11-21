// 放開在空白處的「接上新節點」選單位置（docs/ui/canvas-interaction.md §1）：放開點右側 16px，放不下改左側，不蓋住來源出口。
import { CARD_W, HANDLE_R, outletY } from '../graph/card'
import type { Pt } from '../graph/route'

export const MENU_W = 240
export const MENU_H = 440
export const MENU_GAP = 16
/** 按下與放開距離小於這個值當作點一下，不開選單 */
export const DRAG_MIN = 6

export interface LinkMenu {
  /** 選單左上角（畫布容器內的像素） */
  x: number
  y: number
  /** 放開處（流程座標） */
  flow: Pt
  from: { id: string; port: string }
  /** 來源出口與放開處（畫布容器內的像素），畫保留的虛線 */
  start: Pt
  drop: Pt
}

export interface MenuInput {
  /** 按下時的螢幕座標；沒有就不判斷距離 */
  press: Pt | null
  /** 放開時的螢幕座標 */
  pointer: Pt
  /** 畫布容器的左上角與大小 */
  box: { left: number; top: number; w: number; h: number }
  flow: Pt
  from: { id: string; port: string }
  /** 來源節點座標、出口總數與該出口序號、目前 viewport */
  node: Pt | undefined
  outputs: number
  index: number
  viewport: { x: number; y: number; zoom: number }
}

/** 拖曳距離太短（只是點一下圓點）回傳 null */
export function placeLinkMenu(i: MenuInput): LinkMenu | null {
  if (i.press && Math.hypot(i.pointer.x - i.press.x, i.pointer.y - i.press.y) < DRAG_MIN) return null
  const { w, h } = i.box
  const drop = { x: i.pointer.x - i.box.left, y: i.pointer.y - i.box.top }
  let x = drop.x + MENU_GAP
  if (w > 0 && x + MENU_W > w - 8) x = Math.max(8, drop.x - MENU_GAP - MENU_W)
  const maxY = Math.max(8, h - MENU_H - 8)
  let y = h > 0 ? Math.min(Math.max(8, drop.y - 24), maxY) : drop.y
  const vp = i.viewport
  const start = i.node
    ? { x: vp.x + (i.node.x + CARD_W) * vp.zoom, y: vp.y + (i.node.y + outletY(i.index, i.outputs)) * vp.zoom }
    : drop
  if (start.x >= x - HANDLE_R && start.x <= x + MENU_W + HANDLE_R && start.y >= y - HANDLE_R && start.y <= y + MENU_H + HANDLE_R) {
    y = Math.min(start.y + 24, maxY)
  }
  return { x, y, flow: i.flow, from: i.from, start, drop }
}
