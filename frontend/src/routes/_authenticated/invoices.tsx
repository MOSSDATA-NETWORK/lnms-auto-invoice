import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { InvoicesPage } from '@/features/invoices/invoices-page'

export const Route = createFileRoute('/_authenticated/invoices')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, [
      'invoice.finalize',
      'invoice.send',
      'invoice.void',
    ]),
  component: InvoicesPage,
})
