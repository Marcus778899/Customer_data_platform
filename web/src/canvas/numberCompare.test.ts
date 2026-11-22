import { describe, expect, it } from 'vitest'
import { fieldKind, needsKindToggle, numericInput, parseValue, textLooksNumber, valueKindLabel } from './eventFilter'

describe('欄位型別對照（F-33）', () => {
  it('內建數字欄、文字欄；amount 與其他不明的鍵是 unknown', () => {
    for (const k of ['cart_total', 'price', 'quantity']) expect(fieldKind(`properties.${k}`)).toBe('num')
    expect(fieldKind('trigger.properties.cart_total')).toBe('num')
    expect(fieldKind('properties.utm_source')).toBe('text')
    expect(fieldKind('properties.amount')).toBe('unknown')
    expect(fieldKind('tier')).toBe('unknown')
  })
  it('分組、切換、數字鍵盤、型別標示', () => {
    expect(needsKindToggle('properties.amount', 'EQ')).toBe(true)
    expect(needsKindToggle('properties.amount', 'GT')).toBe(false)
    expect(needsKindToggle('', 'EQ')).toBe(false)
    expect(needsKindToggle('properties.price', 'EQ')).toBe(false)
    expect(numericInput('GT', false)).toBe(true)
    expect(numericInput('EQ', true)).toBe(true)
    expect(numericInput('EQ', false)).toBe(false)
    expect(valueKindLabel('GT', false)).toBe('（數字）')
    expect(valueKindLabel('EQ', false)).toBe('（文字）')
    expect(valueKindLabel('CONTAINS', true)).toBe('（文字）')
    expect(valueKindLabel('IS_NULL', false)).toBe('')
  })
})

describe('數字驗證', () => {
  it.each(['1,000', '500元', '1e3', '.5', '5 0', '１２', '1234567890123456'])('比大小 %s 不是數字', (t) => {
    const p = parseValue({ operator: 'GT', text: t, num: false })
    expect(p).toMatchObject({ ok: false, error: expect.stringContaining('要填數字') })
  })
  it('負數與小數', () => {
    expect(parseValue({ operator: 'LTE', text: ' -1.5 ', num: false })).toEqual({ ok: true, value: -1.5 })
  })
  it('數字型 IN 指出第幾項', () => {
    expect(parseValue({ operator: 'IN', text: '1, b', num: true })).toMatchObject({ ok: false, error: expect.stringMatching(/第 2 項『b』不是數字/) })
  })
  it('文字像數字時提示（不是錯誤），數字型不提示', () => {
    expect(textLooksNumber({ operator: 'EQ', text: '500', num: false }, 'properties.amount')).toContain('會當成文字 "500"')
    expect(textLooksNumber({ operator: 'IN', text: '1, a, 2', num: false }, 'properties.amount')).toContain('"1"、"2"')
    expect(textLooksNumber({ operator: 'EQ', text: 'abc', num: false }, 'properties.amount')).toBeNull()
    expect(textLooksNumber({ operator: 'EQ', text: '500', num: true }, 'properties.amount')).toBeNull()
  })
})
