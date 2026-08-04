import { describe, expect, it } from 'vitest'
import { normalizeAging } from './reports-page'

describe('receivables aging chart data', () => {
  it('normalizes large minor-unit values without converting the amount itself', () => {
    const rows = normalizeAging([
      {
        currency_code: 'CNY',
        bucket: 'CURRENT',
        outstanding_minor: '9007199254740993',
        invoice_count: 1,
      },
      {
        currency_code: 'CNY',
        bucket: '1_30',
        outstanding_minor: '1',
        invoice_count: 1,
      },
    ])

    expect(rows[0].outstanding_minor).toBe('9007199254740993')
    expect(rows[0].chart_ratio).toBe(1)
    expect(rows[1].chart_ratio).toBeGreaterThan(0)
    expect(rows[1].chart_ratio).toBeLessThan(1)
  })
})
