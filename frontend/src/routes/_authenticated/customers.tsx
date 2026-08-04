import { createFileRoute, redirect } from '@tanstack/react-router'
import { CustomersPage } from '@/features/customers/customers-page'

type CustomerSearch = { q?: string }

export const Route = createFileRoute('/_authenticated/customers')({
  validateSearch: (search: Record<string, unknown>): CustomerSearch => ({
    q: typeof search.q === 'string' ? search.q : undefined,
  }),
  beforeLoad: ({ context }) => {
    if (!context.session.permissions.includes('customer.read'))
      throw redirect({ to: '/forbidden' })
  },
  component: CustomersPage,
})
