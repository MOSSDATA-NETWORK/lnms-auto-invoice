import { describe, expect, it } from 'vitest'
import type { PermissionCode, Session } from '@/api/types'
import { dashboardVisibility } from './dashboard-permissions'

describe('dashboardVisibility', () => {
  it('does not expose business metrics to an unrelated permission', () => {
    expect(dashboardVisibility(session('template.publish'))).toEqual({
      customerMetrics: false,
      previewMetrics: false,
      invoiceMetrics: false,
      jobMetrics: false,
      receivableMetrics: false,
    })
  })

  it('matches the backend metric permission groups', () => {
    expect(dashboardVisibility(session('audit.read'))).toEqual({
      customerMetrics: false,
      previewMetrics: false,
      invoiceMetrics: true,
      jobMetrics: true,
      receivableMetrics: true,
    })
    expect(dashboardVisibility(session('system.admin'))).toEqual({
      customerMetrics: false,
      previewMetrics: false,
      invoiceMetrics: false,
      jobMetrics: true,
      receivableMetrics: true,
    })
  })
})

function session(...permissions: PermissionCode[]): Session {
  return {
    user_id: '01900000-0000-7000-8000-000000000001',
    tenant_id: '01900000-0000-7000-8000-000000000002',
    tenant_code: 'tenant',
    username: 'user',
    display_name: 'User',
    permissions,
    must_change_password: false,
    mfa_enabled: false,
  }
}
