// @vitest-environment jsdom
/**
 * node-card-unify：三處節點卡同一組尺寸（docs/ui/canvas-interaction.md §2）。
 * 編輯畫布、旅程頁、軌跡圖的卡片都是寬 200；高 120（沒有人數／狀態列）或 144（有；同一張圖內不混用，
 * 旅程頁有統計與軌跡頁整張 144）。排版與連線接點用同一組常數：每條線的起點在卡右緣中點、終點在卡左緣中點，欄與欄之間不重疊。
 * 假設見 helpers.tsx。進入點、mock 同 trace-branch-ui／journey-canvas-ui fixtures。
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { cleanup, screen, waitFor } from '@testing-library/react'
import {
  STATS3, json, openJourney, openTrace, resetEnv, teardown,
} from '../trace-branch-ui/fixtures'
import { cleanup as cleanupEditor, editorBackend, openEditor, resetEnv as resetEditorEnv, smallDef, teardown as teardownEditor } from '../journey-canvas-ui/fixtures'
import { BOX, mockLayout, restoreLayout } from '../canvas-fit-fix/fixtures'
import { CARD_W, anchorMismatches, mockGraphWidth, readBoxes, restoreGraphWidth } from './helpers'

const heights = () => new Set(readBoxes().map((b) => b.h))
const widths = () => new Set(readBoxes().map((b) => b.w))

describe('編輯畫布', () => {
  beforeEach(() => {
    resetEditorEnv()
    mockLayout()
  })
  afterEach(() => {
    cleanupEditor()
    teardownEditor()
    restoreLayout()
  })

  it('每張卡寬 200、高 120', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(readBoxes().length).toBe(6))
    expect(widths()).toEqual(new Set([200]))
    expect(heights()).toEqual(new Set([120]))
    expect(BOX.w).toBeGreaterThan(0)
  }, 60000)

  it('連線起點在卡右緣（多出口各出口 y 不同）、終點在卡的入口（左緣中點；同欄為上／下緣中點）；相鄰欄的卡不重疊', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    await waitFor(() => expect(document.querySelectorAll('[data-testid^="rf__edge-"] path').length).toBeGreaterThan(0))
    expect(anchorMismatches(readBoxes(), 'editor')).toEqual([])
    const bs = readBoxes()
    for (let i = 0; i < bs.length; i++) {
      for (let j = i + 1; j < bs.length; j++) {
        const overlap = Math.abs(bs[i].x - bs[j].x) < CARD_W && Math.abs(bs[i].y - bs[j].y) < 120
        expect(overlap, `「${bs[i].name}」與「${bs[j].name}」重疊`).toBe(false)
      }
    }
  }, 60000)
})

describe('旅程頁唯讀圖', () => {
  beforeEach(() => {
    resetEnv()
    mockGraphWidth()
  })
  afterEach(() => {
    cleanup()
    teardown()
    restoreGraphWidth()
  })

  it('有節點統計：每張卡寬 200、高 144（含沒有人數的開始節點，同一張圖不混用）', async () => {
    openJourney()
    await waitFor(() => expect(readBoxes().length).toBe(10))
    await waitFor(() => expect(document.body.textContent).toContain('資料時間'))
    expect(widths()).toEqual(new Set([200]))
    expect(heights()).toEqual(new Set([144]))
  })

  it('統計取不到（失敗）：整張圖 120', async () => {
    openJourney({ [`GET ${STATS3}`]: () => json({ title: 'x', status: 500 }, 500) })
    await waitFor(() => expect(readBoxes().length).toBe(10))
    await screen.findByText(/各節點人數暫時取不到/)
    expect(widths()).toEqual(new Set([200]))
    expect(heights()).toEqual(new Set([120]))
  })

  it('連線接點：起點在卡右緣中點、終點在卡左緣中點（144 高的卡取 72）', async () => {
    openJourney()
    await waitFor(() => expect(readBoxes().length).toBe(10))
    await waitFor(() => expect(heights()).toEqual(new Set([144])))
    await waitFor(() => expect(document.querySelectorAll('[data-testid^="rf__edge-"] path.react-flow__edge-path').length).toBeGreaterThan(5))
    expect(anchorMismatches()).toEqual([])
  })

  it('欄與欄之間留得下連線：相鄰欄的卡左緣相距 >= 232（寬 200＋至少 32）', async () => {
    openJourney()
    await waitFor(() => expect(readBoxes().length).toBe(10))
    const xs = [...new Set(readBoxes().map((b) => b.x))].sort((a, b) => a - b)
    for (let i = 1; i < xs.length; i++) expect(xs[i] - xs[i - 1], `第 ${i} 欄與前一欄`).toBeGreaterThanOrEqual(232)
    const bs = readBoxes()
    for (let i = 0; i < bs.length; i++) {
      for (let j = i + 1; j < bs.length; j++) {
        const overlap = Math.abs(bs[i].x - bs[j].x) < 200 && Math.abs(bs[i].y - bs[j].y) < 144
        expect(overlap, `「${bs[i].name}」與「${bs[j].name}」重疊`).toBe(false)
      }
    }
  })
})

describe('軌跡圖', () => {
  beforeEach(() => {
    resetEnv()
    mockGraphWidth()
  })
  afterEach(() => {
    cleanup()
    teardown()
    restoreGraphWidth()
  })

  it('每張卡寬 200、高 144（整張同一高度）', async () => {
    openTrace()
    await waitFor(() => expect(readBoxes().length).toBe(10))
    expect(widths()).toEqual(new Set([200]))
    expect(heights()).toEqual(new Set([144]))
  })

  it('連線接點與卡片尺寸一致', async () => {
    openTrace()
    await waitFor(() => expect(readBoxes().length).toBe(10))
    await waitFor(() => expect(document.querySelectorAll('[data-testid^="rf__edge-"] path.react-flow__edge-path').length).toBeGreaterThan(5))
    expect(anchorMismatches()).toEqual([])
  })
})
