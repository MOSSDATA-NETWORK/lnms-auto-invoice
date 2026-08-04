import { createFileRoute } from '@tanstack/react-router'
import { requireAnyPermission } from '@/auth/route-guard'
import { ServicesPage } from '@/features/services/services-page'

export const Route = createFileRoute('/_authenticated/services')({
  beforeLoad: ({ context }) =>
    requireAnyPermission(context.session, ['customer.read']),
  component: ServicesPage,
})
