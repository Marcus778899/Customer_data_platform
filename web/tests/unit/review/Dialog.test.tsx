// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, it, vi } from 'vitest'
import { Dialog, TextField } from '../../../src/pages/review/Dialog'

afterEach(cleanup)

function Demo({ onClose }: { onClose: () => void }) {
  return (
    <Dialog title="測試" onClose={onClose}>
      <TextField label="原因" value="" onChange={() => undefined} autoFocus />
      <button type="button">確定</button>
    </Dialog>
  )
}

it('Tab 與 Shift+Tab 在對話框內循環；Esc 呼叫 onClose；開啟時焦點在欄位', async () => {
  const close = vi.fn()
  render(<Demo onClose={close} />)
  const box = screen.getByRole('textbox', { name: '原因' })
  const ok = screen.getByRole('button', { name: '確定' })
  expect(document.activeElement).toBe(box)
  await userEvent.tab()
  expect(document.activeElement).toBe(ok)
  await userEvent.tab()
  expect(document.activeElement).toBe(box)
  await userEvent.tab({ shift: true })
  expect(document.activeElement).toBe(ok)
  await userEvent.keyboard('{Escape}')
  expect(close).toHaveBeenCalledTimes(1)
})
