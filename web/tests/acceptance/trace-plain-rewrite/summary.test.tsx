// @vitest-environment jsdom
/** 軌跡頁白話改版：頁首與首屏三問摘要卡（enrollment-trace.md「畫面」1～2、「摘要組句」）。 */
import { cleanup, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  NOW_EARLY, NOW_LATE, aborted, cardText, completed, delivery, failed, freezeNow, heading, mainText, norm,
  openTrace, resetEnv, summaryCard, teardown, unfreeze, waiting, problem, VER,
} from './fixtures'

beforeEach(resetEnv)
afterEach(() => { cleanup(); teardown(); unfreeze() })

describe('頁首', () => {
  it('h1「『旅程名』旅程的進度」、副標「客戶：姓名・加入時間」、狀態徽章用白話狀態字', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({
      trigger_snapshot: { ...waiting().trigger_snapshot, profile_snapshot: { name: '王小明' } },
    }))
    const h = await heading()
    expect(h.textContent).toMatch(/「棄單挽回」旅程的進度/)
    const text = mainText()
    expect(text).toContain('客戶：王小明')
    expect(text).toMatch(/今天 10:00.*加入|加入.*今天 10:00/)
    expect(text).toContain('等待中')
    expect(text).not.toContain('WAITING')
  })

  it('姓名在 masked_paths：副標題照後端給的遮罩值顯示並說明部分資料已遮罩；真實 ID 不出現', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({
      trigger_snapshot: { ...waiting().trigger_snapshot, profile_snapshot: { name: '陳＊＊' } },
      masked_paths: ['trigger_snapshot.profile_snapshot.name'],
    }))
    await heading()
    const text = mainText()
    expect(text).toContain('客戶：陳＊＊')
    expect(text).toContain('已遮罩')
    expect(text).not.toContain('王小明')
  })

  it('標頭有「重新整理」按鈕與「更新」時間（今天 HH:mm 更新）', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    expect(screen.getByRole('button', { name: '重新整理' })).toBeTruthy()
    expect(mainText()).toMatch(/今天 \d{2}:\d{2}\s*更新/)
  })
})

describe('摘要卡：三問', () => {
  it('role=status，依序有三個問題', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const t = cardText()
    const a = t.indexOf('這位客戶現在在哪？')
    const b = t.indexOf('接下來會發生什麼？')
    const c = t.indexOf('訊息有送出嗎？')
    expect(a).toBeGreaterThanOrEqual(0)
    expect(b).toBeGreaterThan(a)
    expect(c).toBeGreaterThan(b)
  })

  it('等待中（TIMER）：在「X」這一步、已等多久、預計何時自動繼續、下一步是「Y」、訊息還沒有', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    const t = cardText()
    expect(t).toContain('在「等 3 天」這一步，等待中')
    expect(t).toContain('已等 1 小時')
    expect(t).toContain('10/21（三）10:00')
    expect(t).toContain('自動繼續')
    expect(t).toContain('下一步是「發送訊息：提醒信」')
    expect(t).toContain('還沒有')
    expect(t).not.toContain('目前狀況')
  })

  it('等待中但取不到旅程定義：照常顯示，「下一步」降級為「暫時無法顯示後續步驟」', async () => {
    freezeNow(NOW_EARLY)
    openTrace({ [`GET ${VER}`]: () => problem(404, 'JOURNEY_NOT_FOUND') })
    await heading()
    const t = cardText()
    expect(t).toContain('在「等 3 天」這一步，等待中')
    expect(t).toContain('10/21（三）10:00')
    expect(t).toContain('自動繼續')
    expect(t).toContain('暫時無法顯示後續步驟')
    expect(t).not.toContain('下一步是')
  })

  it('等待中：實際喚醒可能晚幾分鐘的提醒', async () => {
    freezeNow(NOW_EARLY)
    openTrace()
    await heading()
    expect(cardText()).toMatch(/晚幾分鐘/)
  })

  it('等待中且已有一則送出：訊息欄不是「還沒走到發送」，要提到已送出的那一則', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, waiting({ delivery_history: [delivery(1)] }))
    await heading()
    const t = cardText()
    expect(t).toContain('提醒信')
    expect(t).toContain('送出')
  })

  it('處理中（ACTIVE／PENDING）：說明通常幾秒內到下一步，不顯示時間', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({ status: 'ACTIVE', wake_condition: null }))
    await heading()
    const t = cardText()
    expect(t).toContain('處理中')
    expect(t).toContain('幾秒')
    expect(t).not.toMatch(/\d{1,2}:\d{2}/)
  })

  it('走完＋有待送訊息：旅程已走完、不會再有新步驟、N 則排隊、還沒有送達、超過 10 分鐘請聯絡技術人員', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed({ delivery_history: [delivery(1, { status: 'PENDING', delivery_id: null })] }))
    await heading()
    const t = cardText()
    expect(t).toContain('旅程已走完')
    expect(t).toContain('10/20（二）10:00')
    expect(t).toContain('不會再有新的步驟')
    expect(t).toContain('1 則訊息在排隊')
    expect(t).toContain('還沒有送達')
    expect(t).toContain('10 分鐘')
    expect(t).toContain('聯絡技術人員')
  })

  it('走完＋已送出：沒有後續步驟、有。「X」已在時間送出', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed())
    await heading()
    const t = cardText()
    expect(t).toContain('旅程已走完')
    expect(t).toContain('沒有後續步驟')
    expect(t).toMatch(/有。/)
    expect(t).toContain('提醒信')
    expect(t).toContain('10/20（二）10:00')
    expect(t).toContain('送出')
    expect(t).not.toContain('排隊')
  })

  it('發生錯誤（FAILED）：停在「X」因為發生錯誤、不會自動繼續、請不要重複操作；有「複製診斷資訊」', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, failed())
    await heading()
    const t = cardText()
    expect(t).toContain('停在「發送訊息：提醒信」，因為發生錯誤')
    expect(t).toContain('不會自動繼續')
    expect(t).toContain('請不要重複操作')
    expect(t).toContain('沒有')
    expect(summaryCard().querySelector('button')?.textContent).toContain('複製診斷資訊')
  })

  it('「複製診斷資訊」只複製進度編號、時間，不含客戶個資', async () => {
    freezeNow(NOW_LATE)
    const { default: userEvent } = await import('@testing-library/user-event')
    const user = userEvent.setup()
    openTrace({}, failed({
      trigger_snapshot: { ...waiting().trigger_snapshot, profile_snapshot: { name: '王小明', phone: '0912345678' } },
    }))
    await heading()
    await user.click(screen.getByRole('button', { name: '複製診斷資訊' }))
    const copied = await navigator.clipboard.readText()
    expect(copied).toContain('01JD3A5B7C9D1E3F5G7H9J2K4M')
    expect(copied).not.toContain('王小明')
    expect(copied).not.toContain('0912345678')
    expect(copied).not.toContain('01JCX7K2M4N8P3Q6R9S5T1V0WZ')
  })

  it('已中止（ABORTED）：進度已中止，停在「X」；不會再有任何步驟；原因是旅程被停止並寫時間；沒有，之後也不會送出', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, aborted())
    await heading()
    const t = cardText()
    expect(t).toContain('進度已中止，停在「等 3 天」')
    expect(t).toContain('不會再有任何步驟')
    expect(t).toContain('這個旅程已被停止')
    expect(t).toContain('10/19（一）15:20')
    expect(t).toContain('沒有，之後也不會送出')
    expect(t).not.toContain('JOURNEY_STOPPED')
  })

  it('狀態字：走完＝已走完、錯誤＝發生錯誤、中止＝已中止（頁首徽章）', async () => {
    freezeNow(NOW_LATE)
    openTrace({}, completed())
    await heading()
    expect(mainText()).toContain('已走完')
    cleanup()
    openTrace({}, failed())
    await heading()
    expect(norm(document.querySelector('main')!)).toContain('發生錯誤')
    cleanup()
    openTrace({}, aborted())
    await heading()
    expect(norm(document.querySelector('main')!)).toContain('已中止')
  })

  it('WAITING 但 wake_condition 為空不丟例外（降級顯示）', async () => {
    freezeNow(NOW_EARLY)
    openTrace({}, waiting({ status: 'WAITING', wake_condition: null }))
    await heading()
    expect(cardText()).toContain('在「等 3 天」這一步')
  })
})
