// @vitest-environment jsdom
/** canvas-connect-drag：節點庫類別圖示、拖曳把手與移除連線鈕的圖示（docs/ui/canvas-interaction.md §1）。不測圖示路徑，只測 inline svg 與 aria-hidden。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  NINE, cleanup, editorBackend, openEditor, resetEnv, teardown, userEvent, waitFor, libraryItem, screen, smallDef,
} from '../journey-canvas-ui/fixtures'
import { lib } from './helpers'
import { mockLayout, restoreLayout } from '../canvas-fit-fix/fixtures'

beforeEach(() => { resetEnv(); mockLayout(); localStorage.clear() })
afterEach(() => { cleanup(); teardown(); restoreLayout(); localStorage.clear() })

describe('節點庫圖示', () => {
  it('九個項目各有類別圖示（inline svg、aria-hidden）與拖曳把手（內含 svg）；圖示不影響名稱以名稱開頭', async () => {
    editorBackend()
    await openEditor()
    for (const n of NINE) {
      const item = libraryItem(lib(), n)
      const svgs = [...item.querySelectorAll('svg')]
      expect(svgs.length, `「${n}」項目要有類別圖示與把手兩個 svg`).toBeGreaterThanOrEqual(2)
      for (const s of svgs) expect(s.closest('[aria-hidden="true"]'), `「${n}」的 svg 要 aria-hidden`).toBeTruthy()
      const grip = item.querySelector('.nl-grip')
      expect(grip?.querySelector('svg'), `「${n}」拖曳把手要有 svg`).toBeTruthy()
      expect(svgs.some((s) => !grip?.contains(s)), `「${n}」要有把手以外的類別圖示`).toBe(true)
    }
  })
})

describe('移除連線鈕圖示', () => {
  it('選取連線後的「移除連線」鈕含 aria-hidden svg，且 aria-label 為「移除連線」', async () => {
    editorBackend({ def: smallDef(), revision: 1 })
    await openEditor()
    const wrap = await waitFor(() => {
      const w = document.querySelector('[data-testid^="rf__edge-"]')
      expect(w).toBeTruthy()
      return w!
    })
    await userEvent.click(wrap.querySelector('path')!)
    const btn = await waitFor(() => {
      const b = screen.queryAllByRole('button').find((x) => /移除連線/.test(x.getAttribute('aria-label') ?? ''))
      expect(b, '選取連線後要出現「移除連線」鈕').toBeTruthy()
      return b!
    })
    expect(btn.getAttribute('aria-label')).toContain('移除連線')
    const svg = btn.querySelector('svg')
    expect(svg, '移除連線鈕要有圖示').toBeTruthy()
    expect(svg!.closest('[aria-hidden="true"]')).toBeTruthy()
  })
})
