import { describe, expect, it } from 'vitest'
import { normalizeGeneratedApiPath } from './http'

describe('normalizeGeneratedApiPath', () => {
  it('removes the OpenAPI server prefix before using the shared Axios base URL', () => {
    expect(normalizeGeneratedApiPath('/api/v1/invoices/123')).toBe(
      '/invoices/123'
    )
    expect(normalizeGeneratedApiPath('/api/v1')).toBe('/')
  })

  it('leaves unrelated and absolute URLs unchanged', () => {
    expect(normalizeGeneratedApiPath('/v3/api-docs')).toBe('/v3/api-docs')
    expect(normalizeGeneratedApiPath('https://example.com/api/v1')).toBe(
      'https://example.com/api/v1'
    )
    expect(normalizeGeneratedApiPath()).toBeUndefined()
  })
})
