import { createFileRoute, redirect } from '@tanstack/react-router'
import { CustomerDetailPage } from '@/features/customers/customer-detail-page'

export const Route = createFileRoute('/_authenticated/customers/$customerId')({
  beforeLoad: ({ context }) => {
    if (!context.session.permissions.includes('customer.read'))
      throw redirect({ to: '/forbidden' })
  },
  component: CustomerDetailPage,
})
