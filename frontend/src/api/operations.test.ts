import { describe, expect, it } from 'vitest'
import {
  abbreviateMinor,
  compareMinor,
  isValidPositiveAmount,
  money,
  toPositiveMinorUnits,
} from './operations'

describe('minor-unit money helpers', () => {
  it('formats values above Number.MAX_SAFE_INTEGER without losing a cent', () => {
    expect(money('9007199254740993', 'CNY')).toBe('¥90,071,992,547,409.93')
    expect(compareMinor('9007199254740993', '9007199254740992')).toBe(1)
  })

  it('converts major-unit input to an exact bigint-compatible decimal string', () => {
    expect(toPositiveMinorUnits('90071992547409.93', 'CNY')).toBe(
      '9007199254740993'
    )
    expect(toPositiveMinorUnits('9007199254740993', 'JPY')).toBe(
      '9007199254740993'
    )
  })

  it('rejects fractional minor units and values outside PostgreSQL bigint', () => {
    expect(isValidPositiveAmount('0.001', 'CNY')).toBe(false)
    expect(isValidPositiveAmount('92233720368547758.08', 'CNY')).toBe(false)
    expect(() => toPositiveMinorUnits('0.001', 'CNY')).toThrow(
      'Amount cannot be represented exactly in minor units'
    )
  })

  it('abbreviates large chart labels without converting through number', () => {
    expect(abbreviateMinor('1000000000000')).toBe('1T')
    expect(abbreviateMinor('-1250000')).toBe('-1.25M')
  })
})
