import { describe, expect, it } from 'vitest'
import { safeNext } from '../../../src/router'

describe('safeNext', () => {
  it('只接受站內路徑，否則回 /', () => {
    expect(safeNext('?next=%2Fcustomers')).toBe('/customers')
    expect(safeNext('?next=//evil.com')).toBe('/')
    expect(safeNext('?next=%2Flogin')).toBe('/')
    expect(safeNext('')).toBe('/')
  })
})
