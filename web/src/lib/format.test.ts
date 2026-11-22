import { describe, expect, it } from 'vitest'
import type { Reading, Settings } from '../api/client'
import { daysUntil, expiresSoon, formatData, isLowBalance } from './format'

const settings: Settings = { low_balance: 50, expiry_warn_days: 3 }
const today = new Date(2026, 10, 22) // 22 Nov 2026

describe('highlights', () => {
  it('flags expiry within the warning window, including past dates', () => {
    expect(daysUntil('2026-11-25', today)).toBe(3)
    expect(expiresSoon('2026-11-25', settings, today)).toBe(true)
    expect(expiresSoon('2026-11-26', settings, today)).toBe(false)
    expect(expiresSoon('2026-11-20', settings, today)).toBe(true)
    expect(expiresSoon(null, settings, today)).toBe(false)
  })

  it('flags balance under the threshold only when known', () => {
    const r = (balance: number | null) => ({ balance }) as Reading
    expect(isLowBalance(r(5), settings)).toBe(true)
    expect(isLowBalance(r(50), settings)).toBe(false)
    expect(isLowBalance(r(null), settings)).toBe(false)
    expect(isLowBalance(null, settings)).toBe(false)
  })
})

describe('formatData', () => {
  it('uses MB below 1 GB and GB above', () => {
    expect(formatData(0.21)).toBe('0.21 MB')
    expect(formatData(512)).toBe('512 MB')
    expect(formatData(3072)).toBe('3.00 GB')
    expect(formatData(null)).toBe('—')
  })
})
