// @vitest-environment jsdom
/** review 追加：存檔失敗不送審／不驗證；刪除條件列後其餘列的值不錯位。 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { shownText } from '../../../src/canvas/model'
import {
  DRAFT_PATH, cleanup, editorBackend, json, openEditor, resetEnv, screen, selectNode, smallDef, teardown, typeInto,
  userEvent, waitFor, type Def,
} from '../../acceptance/journey-canvas-ui/fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown() })

describe('shownText', () => {
  it('暫存文字仍對應值就沿用，否則顯示值本身', () => {
    const parse = (t: string) => (t === '' ? '' : Number(t))
    expect(shownText('1.', parse, 1)).toBe('1.')
    expect(shownText('舊', (t) => t, 'VIP')).toBe('VIP')
    expect(shownText('', (t) => t, undefined)).toBe('')
  })
})

const fail500 = () => json({ type: 'about:blank', title: 'x', status: 500, code: 'INTERNAL', detail: 'd', request_id: 'R1' }, 500)

describe('存檔失敗時不送審、不驗證', () => {
  it('PUT 5xx 後按送審：不呼叫 submit、不離開編輯頁，顯示儲存失敗', async () => {
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: fail500 } })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_x')
    await screen.findByRole('alert', {}, { timeout: 8000 })
    await userEvent.click(screen.getByRole('button', { name: '送審' }))
    await new Promise((r) => setTimeout(r, 300))
    expect(ed.api.calls.some((c) => c.path.endsWith('/submit'))).toBe(false)
    expect(window.location.pathname).toMatch(/\/edit$/)
  }, 30000)

  it('PUT 5xx 後按驗證：不呼叫 validate', async () => {
    const ed = editorBackend({ routes: { [`PUT ${DRAFT_PATH}`]: fail500 } })
    await openEditor()
    await selectNode('VIP 專屬關心')
    await typeInto('範本代碼', 'cart_x')
    await screen.findByRole('alert', {}, { timeout: 8000 })
    await userEvent.click(screen.getByRole('button', { name: '驗證' }))
    await new Promise((r) => setTimeout(r, 300))
    expect(ed.api.calls.some((c) => c.path.endsWith('/validate'))).toBe(false)
  }, 30000)
})

describe('刪除條件列', () => {
  const def = (): Def => {
    const d = smallDef()
    d.nodes.find((n) => n.id === 'cond_vip')!.config = {
      condition: {
        op: 'AND',
        children: [
          { type: 'attribute', field: 'tier', operator: 'EQ', value: 'AAA' },
          { type: 'attribute', field: 'city', operator: 'EQ', value: 'BBB' },
          { type: 'attribute', field: 'tier', operator: 'EQ', value: 'CCC' },
        ],
      },
    }
    return d
  }
  it('三列刪第一列：其餘列顯示自己的值，改比較不改變值', async () => {
    const ed = editorBackend({ def: def() })
    await openEditor()
    await selectNode('是 VIP？')
    await screen.findAllByLabelText('值')
    await userEvent.click(screen.getAllByRole('button', { name: '移除這個條件' })[0])
    await waitFor(() => expect(screen.getAllByLabelText('值').map((i) => (i as HTMLInputElement).value)).toEqual(['BBB', 'CCC']))
    await userEvent.selectOptions(screen.getAllByLabelText('比較')[0], 'NEQ')
    const put = await waitFor(() => {
      const p = ed.puts().at(-1)
      expect(p).toBeTruthy()
      return p!.body as { definition: Def }
    }, { timeout: 8000 })
    const kids = (put.definition.nodes.find((n) => n.id === 'cond_vip')!.config.condition as { children: { value: string; operator: string }[] }).children
    expect(kids.map((k) => k.value)).toEqual(['BBB', 'CCC'])
    expect(kids[0].operator).toBe('NEQ')
  }, 30000)
})
