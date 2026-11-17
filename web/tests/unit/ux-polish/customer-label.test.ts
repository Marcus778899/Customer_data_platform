import { describe, expect, it } from 'vitest'
import { UNNAMED_CUSTOMER, customerLabel } from '../../../src/format'

describe('customerLabel（客戶標示規則）', () => {
  it('有姓名用姓名', () => {
    expect(customerLabel('陳雅婷', { value: 'c***@example.com' })).toBe('陳雅婷')
  })
  it('沒有姓名用第一個識別碼', () => {
    expect(customerLabel(null, { value: 'c***@example.com' })).toBe('c***@example.com')
    expect(customerLabel('', { value: 'M-1' })).toBe('M-1')
  })
  it('都沒有為「未具名客戶」', () => {
    expect(customerLabel()).toBe(UNNAMED_CUSTOMER)
    expect(customerLabel(null, null)).toBe('未具名客戶')
    expect(customerLabel(undefined, { value: '' })).toBe('未具名客戶')
  })
})
