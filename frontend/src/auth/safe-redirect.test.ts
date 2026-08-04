import { describe, expect, it } from 'vitest'
import { safeRedirectTarget } from './safe-redirect'

describe('safeRedirectTarget', () => {
  it('allows an application-relative path', () => {
    expect(safeRedirectTarget('/invoices?status=OPEN#latest')).toBe(
      '/invoices?status=OPEN#latest'
    )
  })

  it.each([
    'https://evil.example/steal',
    '//evil.example/steal',
    '/\\evil.example/steal',
    '/%5Cevil.example/steal',
    '/%2F%2Fevil.example/steal',
    '/%252F%252Fevil.example/steal',
    '/%255Cevil.example/steal',
    '/invoices%00.evil.example',
    '/invoices%0ASet-Cookie:session=stolen',
    'invoices',
  ])('rejects unsafe redirect %s', (value) => {
    expect(safeRedirectTarget(value)).toBe('/')
  })
})
