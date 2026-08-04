import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { ReportsPage } from '@/features/reports/reports-page'

export const Route = createFileRoute('/_authenticated/reports')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, [
      'payment.record',
      'audit.read',
      'system.admin',
    ]),
  component: ReportsPage,
})
