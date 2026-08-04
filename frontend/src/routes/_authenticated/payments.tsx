import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { PaymentsPage } from '@/features/payments/payments-page'

export const Route = createFileRoute('/_authenticated/payments')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['payment.record']),
  component: PaymentsPage,
})
