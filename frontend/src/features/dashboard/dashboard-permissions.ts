import type { PermissionCode, Session } from '@/api/types'

const previewPermissions: PermissionCode[] = [
  'preview.generate',
  'preview.adjust',
  'preview.approve.business',
  'preview.approve.finance',
  'invoice.finalize',
]

const invoicePermissions: PermissionCode[] = [
  'invoice.finalize',
  'invoice.send',
  'invoice.void',
  'payment.record',
  'audit.read',
]

export function dashboardVisibility(session?: Session) {
  return {
    customerMetrics: hasAny(session, ['customer.read']),
    previewMetrics: hasAny(session, previewPermissions),
    invoiceMetrics: hasAny(session, invoicePermissions),
    jobMetrics: hasAny(session, ['audit.read', 'system.admin']),
    receivableMetrics: hasAny(session, [
      'payment.record',
      'audit.read',
      'system.admin',
    ]),
  }
}

function hasAny(session: Session | undefined, permissions: PermissionCode[]) {
  return permissions.some((permission) =>
    session?.permissions.includes(permission)
  )
}
