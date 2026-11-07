import { describe, expect, it } from 'vitest'
import { stepTitle } from './index'

describe('stepTitle：等待節點名稱（#179）', () => {
  it('節點名「Wait PT2H」沒有 delay 時顯示「等 2 小時」，與軌跡頁一致', () => {
    expect(stepTitle('WaitDelayNode', 'Wait PT2H')).toBe('等 2 小時')
    expect(stepTitle('WaitDelayNode', 'Wait PT2H')).toBe(stepTitle('WaitDelayNode', 'Wait PT2H', 'PT2H'))
  })
  it('其他等待名稱、無法解析的期間維持原名', () => {
    expect(stepTitle('WaitDelayNode', '等客戶考慮')).toBe('等客戶考慮')
    expect(stepTitle('WaitDelayNode', 'Wait soon')).toBe('Wait soon')
  })
})
