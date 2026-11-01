/**
 * plain-copy-glossary 驗收測試（qa 撰寫，worker 不可改）。對照 plain.html#glossary。
 *
 * 介面（worker 照此實作）：`web/src/plain/index.ts` 具名匯出（皆為純函式，未知輸入退回原值字串、不丟例外）
 *   - enrollmentStatusText(status: string): string
 *   - deliveryStatusText(status: string): string
 *   - skipReasonText(reason: string): string
 *   - channelText(channelType: string): string            MOCK -> 測試管道（不會真的送出）
 *   - outPortText(port: string): string                   next/sent/yes/no -> 之後走向
 *   - nodeTypeText(nodeType: string): string              節點型別白話名（node_name 為 null 的退回）
 *   - stepTitle(nodeType: string, nodeName: string | null, delay?: string | null): string | null
 *       StartNode -> null（不顯示）；WaitDelayNode 的 delay 為 ISO 期間
 *   - isoDurationText(iso: string): string                PT15S -> 15 秒；無法解析原樣回傳
 *   - localizeIsoInText(text: string): string             把文字內 ISO UTC 時間換成本地 `YYYY-MM-DD HH:mm[:ss]`
 */
import { describe, expect, it } from 'vitest'
import {
  channelText,
  deliveryStatusText,
  enrollmentStatusText,
  isoDurationText,
  localizeIsoInText,
  nodeTypeText,
  outPortText,
  skipReasonText,
  stepTitle,
} from '../../../src/plain'

process.env.TZ = 'Asia/Taipei'

describe('狀態', () => {
  it.each([
    ['PENDING', '準備中'],
    ['ACTIVE', '進行中'],
    ['WAITING', '等待中'],
    ['SUSPENDED', '已暫停'],
    ['COMPLETED', '已走完'],
    ['GOAL_MET', '已達成目標'],
    ['EXITED', '已提前離開'],
    ['FAILED', '發生錯誤'],
    ['ABORTED', '已中止'],
  ])('%s -> %s', (k, v) => expect(enrollmentStatusText(k)).toBe(v))
})

describe('發送結果與略過原因', () => {
  it.each([
    ['PENDING', '等待發送'],
    ['SENT', '已送出'],
    ['FAILED', '發送失敗'],
    ['CANCELLED', '已取消'],
    ['SKIPPED', '未發送'],
  ])('delivery %s -> %s', (k, v) => expect(deliveryStatusText(k)).toBe(v))

  it.each([
    ['CONSENT', '客戶未同意接收'],
    ['FREQUENCY_CAP', '超過發送次數上限'],
    ['QUIET_HOURS', '在免打擾時段'],
    ['PRIORITY_LOST', '被更優先的訊息取代'],
    ['BUDGET_EXCEEDED', '超出預算'],
    ['KILL_SWITCH', '發送功能已被暫停'],
  ])('skip %s -> %s', (k, v) => expect(skipReasonText(k)).toBe(v))
})

describe('管道與出口', () => {
  it('MOCK', () => expect(channelText('MOCK')).toBe('測試管道（不會真的送出）'))
  it('未知管道顯示原值', () => expect(channelText('LINE')).toBe('LINE'))
  it.each([
    ['next', '往下一步'],
    ['sent', '已送出後往下一步'],
    ['yes', '符合'],
    ['no', '不符合'],
  ])('out_port %s -> %s', (k, v) => expect(outPortText(k)).toBe(v))
})

describe('節點型別白話名與步驟標題', () => {
  it.each([
    ['EventSourceNode', '進入旅程'],
    ['WaitDelayNode', '等待'],
    ['SendMessageNode', '發送訊息'],
    ['EndNode', '旅程結束'],
    ['ConditionNode', '條件判斷'],
    ['SplitTestNode', 'A/B 分組'],
    ['WaitForEventNode', '等待客戶行為'],
    ['MergeNode', '分支合流'],
    ['GoalNode', '達成目標'],
    ['ExitNode', '提前結束'],
  ])('nodeTypeText %s -> %s', (k, v) => expect(nodeTypeText(k)).toBe(v))

  it('EventSourceNode', () => {
    expect(stepTitle('EventSourceNode', '客戶加入購物車')).toBe('客戶加入購物車，進入旅程')
  })
  it('WaitDelayNode 用 ISO 期間轉中文', () => {
    expect(stepTitle('WaitDelayNode', '等待', 'P3D')).toBe('等 3 天')
    expect(stepTitle('WaitDelayNode', null, 'PT2H')).toBe('等 2 小時')
  })
  it('SendMessageNode', () => {
    expect(stepTitle('SendMessageNode', '提醒結帳')).toBe('發送訊息：提醒結帳')
  })
  it('EndNode', () => expect(stepTitle('EndNode', '結束')).toBe('旅程結束'))
  it('StartNode 不顯示', () => expect(stepTitle('StartNode', 'Start')).toBeNull())
  it('v0.3.0 節點：條件判斷：名稱', () => {
    expect(stepTitle('ConditionNode', '是否購買')).toBe('條件判斷：是否購買')
  })
  it('node_name 為 null 才退回型別白話名', () => {
    expect(stepTitle('SendMessageNode', null)).toBe('發送訊息')
    expect(stepTitle('EventSourceNode', null)).toBe('進入旅程')
    expect(stepTitle('ConditionNode', null)).toBe('條件判斷')
    expect(stepTitle('EndNode', null)).toBe('旅程結束')
  })
  it('未知型別不丟例外，回非空字串', () => {
    const t = stepTitle('FooNode', null)
    expect(typeof t).toBe('string')
    expect((t as string).length).toBeGreaterThan(0)
    expect(stepTitle('FooNode', '某步驟')).toContain('某步驟')
  })
  it('WaitDelayNode 缺 delay 不丟例外', () => {
    expect(typeof stepTitle('WaitDelayNode', null)).toBe('string')
    expect(typeof stepTitle('WaitDelayNode', null, null)).toBe('string')
  })
})

describe('ISO 期間轉中文', () => {
  it.each([
    ['PT15S', '15 秒'],
    ['PT30M', '30 分鐘'],
    ['PT2H', '2 小時'],
    ['P3D', '3 天'],
    ['P1DT12H', '1 天 12 小時'],
    ['PT1H30M', '1 小時 30 分鐘'],
  ])('%s -> %s', (k, v) => expect(isoDurationText(k)).toBe(v))
  it('無法解析原樣回傳', () => {
    expect(isoDurationText('abc')).toBe('abc')
    expect(isoDurationText('')).toBe('')
  })
})

describe('decision_reason 內 ISO UTC 轉本地', () => {
  it('單一時間', () => {
    const out = localizeIsoInText('timer fired at 2026-10-21T02:00:00Z, moving on')
    expect(out).toContain('2026-10-21 10:00')
    expect(out).not.toContain('T02:00:00Z')
    expect(out.startsWith('timer fired at ')).toBe(true)
    expect(out.endsWith(', moving on')).toBe(true)
  })
  it('多個時間與含毫秒', () => {
    const out = localizeIsoInText('from 2026-10-21T02:00:00Z to 2026-10-21T16:30:00.123Z')
    expect(out).toContain('2026-10-21 10:00')
    expect(out).toContain('2026-10-22 00:30')
    expect(out).not.toMatch(/\dZ/)
  })
  it('沒有時間或空字串原樣回傳', () => {
    expect(localizeIsoInText('no times here')).toBe('no times here')
    expect(localizeIsoInText('')).toBe('')
  })
})

describe('未知值有合理退回、不丟例外', () => {
  it('回傳原值', () => {
    expect(enrollmentStatusText('WEIRD')).toBe('WEIRD')
    expect(deliveryStatusText('WEIRD')).toBe('WEIRD')
    expect(skipReasonText('WEIRD')).toBe('WEIRD')
    expect(outPortText('weird')).toBe('weird')
    expect(nodeTypeText('WeirdNode')).toBe('WeirdNode')
  })
  it('空字串不丟例外', () => {
    expect(() => enrollmentStatusText('')).not.toThrow()
    expect(() => stepTitle('', null)).not.toThrow()
  })
})
